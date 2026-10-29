package io.github.basekt.payment.memory

import io.github.basekt.payment.CreatePaymentRequest
import io.github.basekt.payment.InsufficientFundsException
import io.github.basekt.payment.OnChainConfirmable
import io.github.basekt.payment.OnChainConfirmation
import io.github.basekt.payment.OnChainTransferBatch
import io.github.basekt.payment.OnChainTransferInstruction
import io.github.basekt.payment.PaymentAmount
import io.github.basekt.payment.PaymentException
import io.github.basekt.payment.PaymentProvider
import io.github.basekt.payment.PaymentStatus
import io.github.basekt.payment.PaymentVerificationException
import io.github.basekt.payment.PayoutCapable
import io.github.basekt.payment.PayoutReceipt
import io.github.basekt.payment.PayoutRequest
import io.github.basekt.payment.ProviderSession
import io.github.basekt.payment.RefundReceipt
import io.github.basekt.payment.RefundRequest
import io.github.basekt.payment.Refundable
import io.github.basekt.payment.VerifiedPaymentEvent
import io.github.basekt.payment.WalletBalance
import io.github.basekt.payment.WebhookPayload
import io.github.basekt.payment.WebhookSignatureException
import io.github.basekt.payment.WebhookVerifiable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val HEX_TX_HASH = Regex("^0x[0-9a-fA-F]{64}$")
private val HEX_ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")

/**
 * A deterministic in-memory [PaymentProvider] for tests, samples and prototypes. Implements [WebhookVerifiable],
 * [OnChainConfirmable], [Refundable] and [PayoutCapable] without network calls.
 *
 * @property id the provider identifier (`"memory"` by default).
 * @property supportedCurrencies uppercase currency codes accepted (`PHP` and `USDT` by default).
 * @property webhookSecret secret used to sign and verify test webhook payloads (`X-Signature` header).
 * @property onChainMode when `true`, [createSession] populates [ProviderSession.onChain] instead of a hosted [ProviderSession.checkoutUrl].
 * @property chainId chain id reported for [OnChainConfirmable] (`1001` Kairos testnet by default).
 * @property tokenAddress lowercase `0x`-prefixed token contract address for simulated on-chain transfers.
 * @property recipientAddress lowercase `0x`-prefixed merchant wallet address for simulated on-chain transfers.
 */
public class InMemoryPaymentProvider(
    override val id: String = "memory",
    override val supportedCurrencies: Set<String> = setOf("PHP", "USDT"),
    public val webhookSecret: String = "whsec_memory",
    public val onChainMode: Boolean = false,
    override val chainId: Long = 1001L,
    tokenAddress: String = "0xd077a400968890eacc75cdc901f0356c943e4fdb",
    recipientAddress: String = "0x1111111111111111111111111111111111111111",
    initialTokenBalance: BigInteger = BigInteger.valueOf(1_000_000_000_000L),
    initialNativeGasRaw: BigInteger = BigInteger("10000000000000000000"),
) : PaymentProvider, WebhookVerifiable, OnChainConfirmable, Refundable, PayoutCapable {

    override val tokenAddress: String = tokenAddress.lowercase()
    override val recipientAddress: String = recipientAddress.lowercase()

    private val sequence = AtomicLong(0)
    private val latestBlock = AtomicLong(100)
    private val sessions = ConcurrentHashMap<String, ProviderSession>()
    private val transfers = ConcurrentHashMap<String, SimulatedTransfer>()
    private val walletMutex = Mutex()
    private var tokenBalanceRaw: BigInteger = initialTokenBalance
    private var nativeBalanceRaw: BigInteger = initialNativeGasRaw

    @Serializable
    private data class WebhookWire(
        val eventId: String,
        val status: PaymentStatus,
        val externalSessionId: String? = null,
        val referenceId: String? = null,
        val paymentReference: String? = null,
        val amount: PaymentAmount? = null,
    )

    private data class SimulatedTransfer(
        val txHash: String,
        val logIndex: Int,
        val blockNumber: Long,
        val reverted: Boolean,
        val tokenAddress: String,
        val fromAddress: String,
        val toAddress: String,
        val amount: PaymentAmount,
    )

    override suspend fun createSession(request: CreatePaymentRequest): ProviderSession {
        val currency = request.amount.currency
        if (currency !in supportedCurrencies) {
            throw PaymentException("Provider '$id' does not support currency '$currency' (supported: ${supportedCurrencies.joinToString()}).")
        }
        val num = sequence.incrementAndGet()
        val externalId = "${id}_sess_$num"
        val instruction = if (onChainMode) {
            OnChainTransferInstruction(
                chainId = chainId,
                networkName = "In-Memory Chain ($chainId)",
                tokenAddress = tokenAddress,
                tokenSymbol = currency,
                decimals = request.amount.decimals,
                recipientAddress = recipientAddress,
                amountRaw = request.amount.minorUnits,
                eip681Uri = "ethereum:$tokenAddress@$chainId/transfer?address=$recipientAddress&uint256=${request.amount.minorUnits}",
            )
        } else {
            null
        }
        val session = ProviderSession(
            externalId = externalId,
            status = PaymentStatus.PENDING,
            amount = request.amount,
            checkoutUrl = if (onChainMode) null else "https://checkout.memory.test/$id/$externalId",
            onChain = instruction,
            expiresAt = request.expiresAt,
            metadata = request.metadata + ("referenceId" to request.referenceId),
        )
        sessions[externalId] = session
        return session
    }

    override suspend fun getSession(externalId: String): ProviderSession? = sessions[externalId]

    /** Builds a signed `(headers, rawBody)` pair for testing webhook delivery to `BaseKtPayments`. */
    public fun signedWebhook(
        eventId: String,
        status: PaymentStatus,
        externalSessionId: String? = null,
        referenceId: String? = null,
        paymentReference: String? = "pay_$eventId",
        amount: PaymentAmount? = null,
    ): Pair<Map<String, String>, String> {
        val body = Json.encodeToString(
            WebhookWire.serializer(),
            WebhookWire(eventId, status, externalSessionId, referenceId, paymentReference, amount),
        )
        val sig = hmacHex(webhookSecret, body)
        return mapOf("X-Signature" to sig) to body
    }

    override suspend fun verifyWebhook(payload: WebhookPayload): VerifiedPaymentEvent {
        val headers = payload.headers
        val rawBody = payload.rawBody
        val sigHeader = headers.entries.firstOrNull { it.key.equals("X-Signature", ignoreCase = true) }?.value
            ?: throw WebhookSignatureException("Missing X-Signature header.")
        val expected = hmacHex(webhookSecret, rawBody)
        if (!constantTimeEquals(expected, sigHeader)) {
            throw WebhookSignatureException("Invalid X-Signature header.")
        }
        val wire = try {
            Json.decodeFromString(WebhookWire.serializer(), rawBody)
        } catch (e: Exception) {
            throw WebhookSignatureException("Malformed webhook JSON body.", e)
        }
        wire.externalSessionId?.let { extId ->
            sessions.computeIfPresent(extId) { _, existing ->
                existing.copy(status = wire.status, paymentReference = wire.paymentReference ?: existing.paymentReference)
            }
        }
        return VerifiedPaymentEvent(
            eventKey = wire.eventId,
            status = wire.status,
            externalSessionId = wire.externalSessionId,
            referenceId = wire.referenceId,
            paymentReference = wire.paymentReference,
            amount = wire.amount,
        )
    }

    /**
     * Records a simulated on-chain transfer in memory and returns its `0x`-prefixed transaction hash.
     */
    public fun recordOnChainTransfer(
        amount: PaymentAmount,
        fromAddress: String = "0x2222222222222222222222222222222222222222",
        toAddress: String = recipientAddress,
        contractAddress: String = tokenAddress,
        reverted: Boolean = false,
        txHash: String = "0x" + sequence.incrementAndGet().toString(16).padStart(64, '0'),
        logIndex: Int = 0,
    ): String {
        val normalizedHash = txHash.lowercase()
        require(HEX_TX_HASH.matches(normalizedHash)) { "txHash '$txHash' must be 0x followed by 64 hex chars." }
        val block = latestBlock.incrementAndGet()
        transfers[normalizedHash] = SimulatedTransfer(
            txHash = normalizedHash,
            logIndex = logIndex,
            blockNumber = block,
            reverted = reverted,
            tokenAddress = contractAddress.lowercase(),
            fromAddress = fromAddress.lowercase(),
            toAddress = toAddress.lowercase(),
            amount = amount,
        )
        return normalizedHash
    }

    override suspend fun confirmTransaction(
        txHash: String,
        expectedAmount: PaymentAmount,
        expectedPayer: String?,
    ): OnChainConfirmation {
        val normalizedHash = txHash.trim().lowercase()
        if (!HEX_TX_HASH.matches(normalizedHash)) {
            throw PaymentVerificationException("Transaction hash '$txHash' is not a 32-byte 0x hex string.")
        }
        val transfer = transfers[normalizedHash]
            ?: throw PaymentVerificationException("Transaction '$normalizedHash' was not found on chain $chainId.")
        if (transfer.reverted) {
            throw PaymentVerificationException("Transaction '$normalizedHash' reverted on-chain.")
        }
        if (transfer.tokenAddress != tokenAddress) {
            throw PaymentVerificationException(
                "Transfer in '$normalizedHash' came from contract '${transfer.tokenAddress}', expected '$tokenAddress'.",
            )
        }
        if (transfer.toAddress != recipientAddress) {
            throw PaymentVerificationException(
                "Transfer in '$normalizedHash' went to '${transfer.toAddress}', expected '$recipientAddress'.",
            )
        }
        if (expectedPayer != null && transfer.fromAddress != expectedPayer.trim().lowercase()) {
            throw PaymentVerificationException(
                "Transfer in '$normalizedHash' came from '${transfer.fromAddress}', expected '${expectedPayer.lowercase()}'.",
            )
        }
        if (transfer.amount.currency != expectedAmount.currency || transfer.amount < expectedAmount) {
            throw PaymentVerificationException(
                "Transfer amount ${transfer.amount.toDecimalString()} ${transfer.amount.currency} is less than expected ${expectedAmount.toDecimalString()} ${expectedAmount.currency}.",
            )
        }
        val currentBlock = latestBlock.get()
        return OnChainConfirmation(
            eventKey = "$chainId:${transfer.txHash}:${transfer.logIndex}",
            chainId = chainId,
            txHash = transfer.txHash,
            logIndex = transfer.logIndex,
            blockNumber = transfer.blockNumber,
            confirmations = maxOf(1L, currentBlock - transfer.blockNumber + 1),
            tokenAddress = transfer.tokenAddress,
            fromAddress = transfer.fromAddress,
            toAddress = transfer.toAddress,
            amount = transfer.amount,
        )
    }

    override suspend fun scanIncomingTransfers(fromBlock: Long, toBlock: Long?): OnChainTransferBatch {
        val upper = toBlock ?: latestBlock.get()
        val matched = transfers.values
            .filter { !it.reverted && it.tokenAddress == tokenAddress && it.toAddress == recipientAddress && it.blockNumber in fromBlock..upper }
            .sortedBy { it.blockNumber }
            .map {
                OnChainConfirmation(
                    eventKey = "$chainId:${it.txHash}:${it.logIndex}",
                    chainId = chainId,
                    txHash = it.txHash,
                    logIndex = it.logIndex,
                    blockNumber = it.blockNumber,
                    confirmations = maxOf(1L, upper - it.blockNumber + 1),
                    tokenAddress = it.tokenAddress,
                    fromAddress = it.fromAddress,
                    toAddress = it.toAddress,
                    amount = it.amount,
                )
            }
        return OnChainTransferBatch(lastScannedBlock = upper, transfers = matched)
    }

    override suspend fun refund(request: RefundRequest): RefundReceipt {
        val current = sessions[request.externalSessionId]
            ?: throw PaymentException("Unknown session '${request.externalSessionId}'.")
        if (request.amount.currency != current.amount.currency || request.amount > current.amount) {
            throw PaymentException("Refund exceeds original session amount.")
        }
        sessions[request.externalSessionId] = current.copy(status = PaymentStatus.REFUNDED)
        return RefundReceipt(
            refundId = "${id}_rfnd_${sequence.incrementAndGet()}",
            amount = request.amount,
            status = "succeeded",
        )
    }

    /** Sets the simulated token and native gas balances for [PayoutCapable] tests. */
    public suspend fun setWalletBalances(tokenRaw: BigInteger, nativeGasRaw: BigInteger) {
        walletMutex.withLock {
            tokenBalanceRaw = tokenRaw
            nativeBalanceRaw = nativeGasRaw
        }
    }

    override suspend fun balance(): WalletBalance = walletMutex.withLock {
        val currency = supportedCurrencies.first()
        WalletBalance(
            walletAddress = recipientAddress,
            tokenBalance = PaymentAmount.ofMinorUnits(tokenBalanceRaw, currency),
            nativeBalanceRaw = nativeBalanceRaw.toString(),
            nativeSymbol = "KAIA",
            nativeDecimals = 18,
        )
    }

    override suspend fun payout(request: PayoutRequest): PayoutReceipt = walletMutex.withLock {
        val normalizedRecipient = request.recipientAddress.trim().lowercase()
        if (!HEX_ADDRESS.matches(normalizedRecipient)) {
            throw PaymentException("Invalid recipient wallet address '${request.recipientAddress}'.")
        }
        val needed = request.amount.rawUnits()
        if (tokenBalanceRaw < needed) {
            throw InsufficientFundsException("Insufficient ${request.amount.currency} balance: have $tokenBalanceRaw, need $needed.")
        }
        val simulatedGas = BigInteger("2500000000000000")
        if (nativeBalanceRaw < simulatedGas) {
            throw InsufficientFundsException("Insufficient native KAIA for gas: have $nativeBalanceRaw, need $simulatedGas.")
        }
        tokenBalanceRaw -= needed
        nativeBalanceRaw -= simulatedGas
        val block = latestBlock.incrementAndGet()
        val txHash = "0x" + sequence.incrementAndGet().toString(16).padStart(64, '0')
        PayoutReceipt(
            transferId = txHash,
            fromAddress = recipientAddress,
            recipientAddress = normalizedRecipient,
            amount = request.amount,
            blockNumber = block,
            explorerUrl = "https://kairos.kaiascan.io/tx/$txHash",
        )
    }

    private fun hmacHex(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.encodeToByteArray(), "HmacSHA256"))
        return mac.doFinal(payload.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
