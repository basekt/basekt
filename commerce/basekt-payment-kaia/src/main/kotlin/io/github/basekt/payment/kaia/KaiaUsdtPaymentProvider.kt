package io.github.basekt.payment.kaia

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
import io.github.basekt.payment.WalletBalance
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.TimeSource

/**
 * Kaia KIP-7 / ERC-20 [PaymentProvider] over Ktor [HttpClient] and standard JSON-RPC 2.0 (configured for official
 * Tether USD `0xd077...4fdb` on Kaia Mainnet by default). Implements [OnChainConfirmable] for inbound checkout
 * verification and `eth_getLogs` scanning, and [PayoutCapable] for gas-checked outbound disbursements when
 * [KaiaSettings.signer] is supplied.
 */
public class KaiaUsdtPaymentProvider private constructor(
    private val settings: KaiaSettings,
    private val httpClient: HttpClient,
    private val ownsHttpClient: Boolean,
) : PaymentProvider, OnChainConfirmable, PayoutCapable, AutoCloseable {

    /**
     * Creates a [KaiaUsdtPaymentProvider] sharing an application-managed Ktor [httpClient]. Closing this provider leaves
     * [httpClient] open.
     */
    public constructor(settings: KaiaSettings, httpClient: HttpClient) : this(
        settings = settings,
        httpClient = httpClient,
        ownsHttpClient = false,
    )

    /**
     * Creates a [KaiaUsdtPaymentProvider] with a dedicated CIO [HttpClient] configured with [KaiaSettings.requestTimeout].
     */
    public constructor(settings: KaiaSettings) : this(
        settings = settings,
        httpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = settings.requestTimeout.inWholeMilliseconds
                connectTimeoutMillis = settings.requestTimeout.inWholeMilliseconds
            }
        },
        ownsHttpClient = true,
    )

    private val rpc = KaiaRpcClient(settings.rpcUrl, settings.requestTimeout, httpClient)

    override val id: String get() = settings.id

    override val supportedCurrencies: Set<String> = setOf(settings.tokenSymbol.uppercase())

    override val chainId: Long get() = settings.chainId

    override val tokenAddress: String get() = settings.normalizedTokenAddress

    override val recipientAddress: String get() = settings.normalizedRecipientAddress

    private val chainVerified = AtomicBoolean(false)
    private val payoutMutex = Mutex()
    private val recentSessions = ConcurrentHashMap<String, ProviderSession>()

    override suspend fun createSession(request: CreatePaymentRequest): ProviderSession {
        validateAmountCurrency(request.amount)
        request.payerAddress?.let { payer ->
            try {
                EvmAbi.normalizeAddress(payer, "payerAddress")
            } catch (e: IllegalArgumentException) {
                throw PaymentException(e.message ?: "Invalid payerAddress.", e)
            }
        }
        val externalId = "kaia_${UUID.randomUUID().toString().replace("-", "")}"
        val eip681 = EvmAbi.eip681TransferUri(
            tokenAddress = tokenAddress,
            chainId = chainId,
            recipientAddress = recipientAddress,
            amountRaw = request.amount.rawUnits(),
        )
        val explorer = settings.explorerBaseUrl?.trimEnd('/')?.let { "$it/account/$recipientAddress" }
        val instruction = OnChainTransferInstruction(
            chainId = chainId,
            networkName = settings.networkName,
            tokenAddress = tokenAddress,
            tokenSymbol = settings.tokenSymbol.uppercase(),
            decimals = settings.tokenDecimals,
            recipientAddress = recipientAddress,
            amountRaw = request.amount.minorUnits,
            eip681Uri = eip681,
            explorerUrl = explorer,
        )
        val session = ProviderSession(
            externalId = externalId,
            status = PaymentStatus.PENDING,
            amount = request.amount,
            checkoutUrl = null,
            onChain = instruction,
            expiresAt = request.expiresAt,
            metadata = request.metadata + ("referenceId" to request.referenceId),
        )
        if (recentSessions.size > 4_096) recentSessions.clear()
        recentSessions[externalId] = session
        return session
    }

    override suspend fun getSession(externalId: String): ProviderSession? = recentSessions[externalId]

    override suspend fun confirmTransaction(
        txHash: String,
        expectedAmount: PaymentAmount,
        expectedPayer: String?,
    ): OnChainConfirmation {
        validateAmountCurrency(expectedAmount)
        val normalizedHash = EvmAbi.normalizeTxHashOrNull(txHash)
            ?: throw PaymentVerificationException("Transaction hash '$txHash' must be 0x followed by 64 hex characters.")
        val normalizedExpectedPayer = expectedPayer?.let { payer ->
            try {
                EvmAbi.normalizeAddress(payer, "expectedPayer")
            } catch (e: IllegalArgumentException) {
                throw PaymentVerificationException(e.message ?: "Invalid expectedPayer.", e)
            }
        }

        ensureChainIdMatches()

        val receipt = rpc.getTransactionReceipt(normalizedHash)
            ?: throw PaymentVerificationException("Transaction '$normalizedHash' was not found or is not yet mined on ${settings.networkName}.")
        val receiptBlock = receipt.blockNumber
            ?: throw PaymentVerificationException("Transaction '$normalizedHash' is still pending on ${settings.networkName}.")
        if (receipt.status != 1) {
            throw PaymentVerificationException("Transaction '$normalizedHash' reverted on-chain (status=${receipt.status}).")
        }

        val latestBlock = rpc.blockNumber()
        val confirmations = (latestBlock - receiptBlock + 1L).coerceAtLeast(0L)
        if (confirmations < settings.minConfirmations) {
            throw PaymentVerificationException(
                "Transaction '$normalizedHash' has $confirmations confirmation(s); at least ${settings.minConfirmations} required.",
            )
        }

        val expectedRaw = expectedAmount.rawUnits()
        var wrongContractSeen = false
        var wrongRecipientSeen = false
        var wrongPayerSeen = false
        var wrongAmountSeen: BigInteger? = null

        for (log in receipt.logs) {
            if (log.removed) continue
            val decoded = EvmAbi.decodeTransferLog(log.topics, log.data) ?: continue
            if (log.address != tokenAddress) {
                wrongContractSeen = true
                continue
            }
            if (decoded.toAddress != recipientAddress) {
                wrongRecipientSeen = true
                continue
            }
            if (normalizedExpectedPayer != null && decoded.fromAddress != normalizedExpectedPayer) {
                wrongPayerSeen = true
                continue
            }
            val amountMatches = if (settings.requireExactAmount) {
                decoded.amountRaw == expectedRaw
            } else {
                decoded.amountRaw >= expectedRaw
            }
            if (!amountMatches) {
                wrongAmountSeen = decoded.amountRaw
                continue
            }

            return OnChainConfirmation(
                eventKey = "$chainId:$normalizedHash:${log.logIndex}",
                chainId = chainId,
                txHash = normalizedHash,
                logIndex = log.logIndex,
                blockNumber = receiptBlock,
                confirmations = confirmations,
                tokenAddress = tokenAddress,
                fromAddress = decoded.fromAddress,
                toAddress = decoded.toAddress,
                amount = PaymentAmount.ofMinorUnits(decoded.amountRaw, settings.tokenSymbol.uppercase(), settings.tokenDecimals),
            )
        }

        val reason = when {
            wrongAmountSeen != null -> {
                val got = PaymentAmount.ofMinorUnits(wrongAmountSeen, settings.tokenSymbol.uppercase(), settings.tokenDecimals).toDecimalString()
                val wanted = expectedAmount.toDecimalString()
                if (settings.requireExactAmount) {
                    "Transferred $got ${settings.tokenSymbol}, expected exact amount $wanted ${settings.tokenSymbol}."
                } else {
                    "Transferred $got ${settings.tokenSymbol}, which is below expected $wanted ${settings.tokenSymbol}."
                }
            }
            wrongPayerSeen -> "Transfer in '$normalizedHash' did not originate from expected payer ${EvmAbi.maskAddress(normalizedExpectedPayer)}."
            wrongRecipientSeen -> "Transfer in '$normalizedHash' was not sent to merchant recipient ${EvmAbi.maskAddress(recipientAddress)}."
            wrongContractSeen -> "Transfer in '$normalizedHash' came from an unaccepted token contract (expected ${EvmAbi.maskAddress(tokenAddress)})."
            else -> "Transaction '$normalizedHash' contains no ${settings.tokenSymbol} Transfer log."
        }
        throw PaymentVerificationException(reason)
    }

    override suspend fun scanIncomingTransfers(fromBlock: Long, toBlock: Long?): OnChainTransferBatch {
        require(fromBlock >= 0L) { "fromBlock ($fromBlock) must be >= 0." }
        ensureChainIdMatches()
        val latest = rpc.blockNumber()
        val safeLatest = (latest - settings.minConfirmations + 1L).coerceAtLeast(0L)
        val upper = (toBlock?.coerceAtMost(safeLatest) ?: safeLatest)
        if (upper < fromBlock) {
            return OnChainTransferBatch(lastScannedBlock = fromBlock - 1L, transfers = emptyList())
        }

        val collected = mutableListOf<OnChainConfirmation>()
        var cursor = fromBlock
        while (cursor <= upper) {
            val chunkEnd = minOf(upper, cursor + settings.maxLogBlockSpan - 1L)
            val logs = rpc.getTransferLogs(
                tokenAddress = tokenAddress,
                recipientAddress = recipientAddress,
                fromBlock = cursor,
                toBlock = chunkEnd,
            )
            for (log in logs) {
                if (log.removed || log.address != tokenAddress) continue
                val decoded = EvmAbi.decodeTransferLog(log.topics, log.data) ?: continue
                if (decoded.toAddress != recipientAddress || decoded.amountRaw <= BigInteger.ZERO) continue
                val confirmations = (latest - log.blockNumber + 1L).coerceAtLeast(1L)
                if (confirmations < settings.minConfirmations) continue
                collected += OnChainConfirmation(
                    eventKey = "$chainId:${log.transactionHash}:${log.logIndex}",
                    chainId = chainId,
                    txHash = log.transactionHash,
                    logIndex = log.logIndex,
                    blockNumber = log.blockNumber,
                    confirmations = confirmations,
                    tokenAddress = tokenAddress,
                    fromAddress = decoded.fromAddress,
                    toAddress = decoded.toAddress,
                    amount = PaymentAmount.ofMinorUnits(decoded.amountRaw, settings.tokenSymbol.uppercase(), settings.tokenDecimals),
                )
            }
            cursor = chunkEnd + 1L
        }
        return OnChainTransferBatch(lastScannedBlock = upper, transfers = collected)
    }

    override suspend fun balance(): WalletBalance {
        val wallet = settings.signer?.let { EvmAbi.normalizeAddress(it.address, "signer.address") } ?: recipientAddress
        ensureChainIdMatches()
        val tokenRaw = rpc.tokenBalance(tokenAddress, wallet)
        val nativeRaw = rpc.nativeBalance(wallet)
        return WalletBalance(
            walletAddress = wallet,
            tokenBalance = PaymentAmount.ofMinorUnits(tokenRaw, settings.tokenSymbol.uppercase(), settings.tokenDecimals),
            nativeBalanceRaw = nativeRaw.toString(),
            nativeSymbol = "KAIA",
            nativeDecimals = 18,
        )
    }

    override suspend fun payout(request: PayoutRequest): PayoutReceipt {
        val signer = settings.signer
            ?: throw PaymentException("Kaia provider '$id' has no KaiaTransactionSigner configured for outbound payouts.")
        validateAmountCurrency(request.amount)
        val normalizedRecipient = try {
            EvmAbi.normalizeAddress(request.recipientAddress, "recipientAddress")
        } catch (e: IllegalArgumentException) {
            throw PaymentException(e.message ?: "Invalid recipient address.", e)
        }
        val sender = EvmAbi.normalizeAddress(signer.address, "signer.address")
        val amountRaw = request.amount.rawUnits()

        ensureChainIdMatches()

        // Serialize balance check + nonce lookup + broadcast so concurrent coroutine payouts never collide on nonces.
        val txHash = payoutMutex.withLock {
            val currentTokenRaw = rpc.tokenBalance(tokenAddress, sender)
            if (currentTokenRaw < amountRaw) {
                val have = PaymentAmount.ofMinorUnits(currentTokenRaw, settings.tokenSymbol.uppercase(), settings.tokenDecimals).toDecimalString()
                throw InsufficientFundsException(
                    "Insufficient ${settings.tokenSymbol} balance in ${EvmAbi.maskAddress(sender)}: have $have, need ${request.amount.toDecimalString()}.",
                )
            }

            val calldata = EvmAbi.encodeTransfer(normalizedRecipient, amountRaw)
            val bufferMultiplier = BigInteger.valueOf(settings.gasBufferPercent.toLong())
            val hundred = BigInteger.valueOf(100L)

            val rawGasPrice = rpc.gasPrice()
            val bufferedGasPrice = rawGasPrice.multiply(bufferMultiplier).divide(hundred)
            val estimatedGas = runCatching { rpc.estimateGas(sender, tokenAddress, calldata) }
                .map { it.multiply(bufferMultiplier).divide(hundred) }
                .getOrDefault(settings.fallbackGasLimit)
            val requiredNativeFee = bufferedGasPrice.multiply(estimatedGas)

            val nativeBalance = rpc.nativeBalance(sender)
            if (nativeBalance < requiredNativeFee) {
                val haveKaia = BigDecimal(nativeBalance).movePointLeft(18).stripTrailingZeros().toPlainString()
                val needKaia = BigDecimal(requiredNativeFee).movePointLeft(18).stripTrailingZeros().toPlainString()
                throw InsufficientFundsException(
                    "Insufficient native KAIA for gas in ${EvmAbi.maskAddress(sender)}: have $haveKaia KAIA, need ~$needKaia KAIA.",
                )
            }

            val nonce = rpc.transactionCount(sender)
            val unsigned = UnsignedEvmTransaction(
                chainId = chainId,
                nonce = nonce,
                gasPrice = bufferedGasPrice,
                gasLimit = estimatedGas,
                to = tokenAddress,
                value = BigInteger.ZERO,
                data = calldata,
            )
            val signedHex = signer.sign(unsigned)
            rpc.sendRawTransaction(signedHex).lowercase()
        }

        val receipt = awaitReceipt(txHash)
        if (receipt.status != 1) {
            throw PaymentException("Kaia payout transaction '$txHash' reverted on-chain.")
        }
        val explorerUrl = settings.explorerBaseUrl?.trimEnd('/')?.let { "$it/tx/$txHash" }
        return PayoutReceipt(
            transferId = txHash,
            fromAddress = sender,
            recipientAddress = normalizedRecipient,
            amount = request.amount,
            blockNumber = receipt.blockNumber,
            explorerUrl = explorerUrl,
        )
    }

    /** Closes the internal [HttpClient] if created by this provider; no-op when a shared [HttpClient] was supplied. */
    override fun close() {
        if (ownsHttpClient) {
            httpClient.close()
        }
    }

    private suspend fun awaitReceipt(txHash: String): RpcTransactionReceipt {
        val mark = TimeSource.Monotonic.markNow()
        while (true) {
            val receipt = rpc.getTransactionReceipt(txHash)
            if (receipt != null && receipt.blockNumber != null) return receipt
            if (mark.elapsedNow() >= settings.receiptTimeout) {
                throw PaymentException("Timed out after ${settings.receiptTimeout} waiting for Kaia receipt of '$txHash'.")
            }
            delay(settings.receiptPollInterval)
        }
    }

    private suspend fun ensureChainIdMatches() {
        if (chainVerified.get()) return
        val remoteChainId = rpc.chainId()
        if (remoteChainId != chainId) {
            throw PaymentVerificationException(
                "Kaia RPC endpoint reported chainId $remoteChainId, expected $chainId (${settings.networkName}).",
            )
        }
        chainVerified.set(true)
    }

    private fun validateAmountCurrency(amount: PaymentAmount) {
        val expectedCurrency = settings.tokenSymbol.uppercase()
        if (amount.currency != expectedCurrency || amount.decimals != settings.tokenDecimals) {
            throw PaymentException(
                "Kaia provider '$id' expects $expectedCurrency with ${settings.tokenDecimals} decimals, got ${amount.currency} (decimals=${amount.decimals}).",
            )
        }
    }
}
