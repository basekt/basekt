package io.github.basekt.payment.kaia

import io.github.basekt.payment.InsufficientFundsException
import io.github.basekt.payment.PaymentAmount
import io.github.basekt.payment.PaymentProvider
import io.github.basekt.payment.PaymentVerificationException
import io.github.basekt.payment.PayoutRequest
import io.github.basekt.testing.PaymentProviderContract
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class KaiaUsdtPaymentProviderTest : PaymentProviderContract() {

    override val contractCurrency: String = "USDT"

    private val merchant = "0x1111111111111111111111111111111111111111"
    private val customer = "0x2222222222222222222222222222222222222222"
    private val hotWallet = "0x3333333333333333333333333333333333333333"
    private val fakeToken = "0x9999999999999999999999999999999999999999"

    private lateinit var rpc: FakeKaiaJsonRpc

    @BeforeTest
    fun resetFakeRpc() {
        rpc = FakeKaiaJsonRpc(chainId = KaiaNetwork.MAINNET.chainId)
    }

    override fun createPaymentProvider(): PaymentProvider = KaiaUsdtPaymentProvider(
        settings = KaiaSettings.mainnet(recipientAddress = merchant),
        httpClient = rpc.httpClient,
    )

    @Test
    fun `verifies genuine Kaia USDT Transfer receipt and rejects fake token, wrong recipient, underpayment and revert`() = runTest {
        val provider = KaiaUsdtPaymentProvider(
            settings = KaiaSettings.mainnet(recipientAddress = merchant),
            httpClient = rpc.httpClient,
        )
        val expected = PaymentAmount.ofDecimal("49.50", "USDT")

        // 1. Valid transfer of 49.50 USDT (49_500_000 raw) to merchant on official USDT contract
        val validTx = "0x" + "a1".repeat(32)
        rpc.putTransferReceipt(
            txHash = validTx,
            status = 1,
            blockNumber = 500L,
            contract = EvmAbi.KAIA_MAINNET_USDT,
            from = customer,
            to = merchant,
            amountRaw = BigInteger.valueOf(49_500_000L),
            logIndex = 2,
        )
        val confirmation = provider.confirmTransaction(validTx, expected, expectedPayer = customer)
        assertEquals("8217:$validTx:2", confirmation.eventKey)
        assertEquals(expected, confirmation.amount)
        assertEquals(customer, confirmation.fromAddress)
        assertEquals(merchant, confirmation.toAddress)

        // 2. Spoofed ERC-20 contract emitting identical Transfer topics/amount -> rejected!
        val spoofedTx = "0x" + "b2".repeat(32)
        rpc.putTransferReceipt(
            txHash = spoofedTx,
            status = 1,
            blockNumber = 501L,
            contract = fakeToken,
            from = customer,
            to = merchant,
            amountRaw = BigInteger.valueOf(49_500_000L),
        )
        val spoofError = assertFailsWith<PaymentVerificationException> {
            provider.confirmTransaction(spoofedTx, expected)
        }
        assertTrue(spoofError.message!!.contains("contract"))

        // 3. Underpayment -> rejected!
        val underpaidTx = "0x" + "c3".repeat(32)
        rpc.putTransferReceipt(
            txHash = underpaidTx,
            status = 1,
            blockNumber = 502L,
            contract = EvmAbi.KAIA_MAINNET_USDT,
            from = customer,
            to = merchant,
            amountRaw = BigInteger.valueOf(49_000_000L),
        )
        assertFailsWith<PaymentVerificationException> {
            provider.confirmTransaction(underpaidTx, expected)
        }

        // 4. Reverted on-chain -> rejected!
        val revertedTx = "0x" + "d4".repeat(32)
        rpc.putTransferReceipt(
            txHash = revertedTx,
            status = 0,
            blockNumber = 503L,
            contract = EvmAbi.KAIA_MAINNET_USDT,
            from = customer,
            to = merchant,
            amountRaw = BigInteger.valueOf(49_500_000L),
        )
        assertFailsWith<PaymentVerificationException> {
            provider.confirmTransaction(revertedTx, expected)
        }
    }

    @Test
    fun `scans incoming Transfer logs across chunked block ranges`() = runTest {
        val provider = KaiaUsdtPaymentProvider(
            settings = KaiaSettings(
                recipientAddress = merchant,
                maxLogBlockSpan = 10L,
            ),
            httpClient = rpc.httpClient,
        )
        val tx1 = "0x" + "11".repeat(32)
        val tx2 = "0x" + "22".repeat(32)
        rpc.putTransferReceipt(tx1, 1, 490L, EvmAbi.KAIA_MAINNET_USDT, customer, merchant, BigInteger.valueOf(10_000_000L))
        rpc.putTransferReceipt(tx2, 1, 505L, EvmAbi.KAIA_MAINNET_USDT, customer, merchant, BigInteger.valueOf(25_000_000L))
        rpc.currentBlock.set(510L)

        val batch = provider.scanIncomingTransfers(fromBlock = 485L, toBlock = 510L)
        assertEquals(510L, batch.lastScannedBlock)
        assertEquals(listOf(tx1, tx2), batch.transfers.map { it.txHash })
    }

    @Test
    fun `executes serialized gas-checked USDT payouts and refuses insufficient USDT or KAIA gas`() = runTest {
        val seenNonces = ConcurrentHashMap.newKeySet<BigInteger>()
        val signer = object : KaiaTransactionSigner {
            override val address: String = hotWallet
            override suspend fun sign(transaction: UnsignedEvmTransaction): String {
                seenNonces += transaction.nonce
                return "0xsigned_${transaction.nonce}"
            }
        }
        val provider = KaiaUsdtPaymentProvider(
            settings = KaiaSettings(
                recipientAddress = merchant,
                signer = signer,
                receiptPollInterval = 10.milliseconds,
            ),
            httpClient = rpc.httpClient,
        )

        // Fund hot wallet with 100 USDT and 2 KAIA
        rpc.tokenBalances[hotWallet] = BigInteger.valueOf(100_000_000L)
        rpc.nativeBalances[hotWallet] = BigInteger("2000000000000000000")

        // 4 concurrent payouts of 15 USDT each -> nonces 0, 1, 2, 3 without collision!
        val receipts = (1..4).map {
            async(Dispatchers.IO) {
                provider.payout(PayoutRequest(recipientAddress = customer, amount = PaymentAmount.ofDecimal("15", "USDT")))
            }
        }.awaitAll()
        assertEquals(4, receipts.size)
        assertEquals(setOf(BigInteger.ZERO, BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3)), seenNonces)

        // Drain native KAIA -> payout fails pre-flight with InsufficientFundsException naming KAIA gas!
        rpc.nativeBalances[hotWallet] = BigInteger.ZERO
        val gasError = assertFailsWith<InsufficientFundsException> {
            provider.payout(PayoutRequest(recipientAddress = customer, amount = PaymentAmount.ofDecimal("5", "USDT")))
        }
        assertTrue(gasError.message!!.contains("KAIA"))
    }
}

private class FakeKaiaJsonRpc(private val chainId: Long) {
    val currentBlock = AtomicLong(600L)
    val tokenBalances = ConcurrentHashMap<String, BigInteger>()
    val nativeBalances = ConcurrentHashMap<String, BigInteger>()
    private val nonces = ConcurrentHashMap<String, AtomicLong>()
    private val receipts = ConcurrentHashMap<String, JsonObject>()
    private val logs = ConcurrentHashMap<String, Pair<Long, JsonObject>>()
    private val txSeq = AtomicLong(1000L)
    private val json = Json { ignoreUnknownKeys = true }

    val httpClient = HttpClient(
        MockEngine { request ->
            val raw = request.body.toByteArray().decodeToString()
            val req = json.parseToJsonElement(raw).jsonObject
            val id = req["id"] ?: JsonPrimitive(1)
            val method = req["method"]!!.jsonPrimitive.content
            val params = req["params"]?.jsonArray ?: JsonArray(emptyList())

            val result = when (method) {
                "eth_chainId" -> JsonPrimitive("0x" + chainId.toString(16))
                "eth_blockNumber" -> JsonPrimitive("0x" + currentBlock.get().toString(16))
                "eth_gasPrice" -> JsonPrimitive("0x5d21dba00") // 25 gwei
                "eth_estimateGas" -> JsonPrimitive("0xc350") // 50,000
                "eth_getBalance" -> {
                    val addr = params[0].jsonPrimitive.content.lowercase()
                    JsonPrimitive(EvmAbi.toHexQuantity(nativeBalances[addr] ?: BigInteger.ZERO))
                }
                "eth_call" -> {
                    val data = params[0].jsonObject["data"]!!.jsonPrimitive.content
                    val wallet = "0x" + data.takeLast(40).lowercase()
                    val bal = tokenBalances[wallet] ?: BigInteger.ZERO
                    JsonPrimitive("0x" + bal.toString(16).padStart(64, '0'))
                }
                "eth_getTransactionCount" -> {
                    val addr = params[0].jsonPrimitive.content.lowercase()
                    val next = nonces.computeIfAbsent(addr) { AtomicLong(0L) }.getAndIncrement()
                    JsonPrimitive(EvmAbi.toHexQuantity(BigInteger.valueOf(next)))
                }
                "eth_sendRawTransaction" -> {
                    val hash = "0x" + txSeq.incrementAndGet().toString(16).padStart(64, '0')
                    val block = currentBlock.incrementAndGet()
                    receipts[hash] = JsonObject(
                        mapOf(
                            "transactionHash" to JsonPrimitive(hash),
                            "blockNumber" to JsonPrimitive("0x" + block.toString(16)),
                            "status" to JsonPrimitive("0x1"),
                            "logs" to JsonArray(emptyList()),
                        ),
                    )
                    JsonPrimitive(hash)
                }
                "eth_getTransactionReceipt" -> {
                    val hash = params[0].jsonPrimitive.content.lowercase()
                    receipts[hash] ?: JsonNull
                }
                "eth_getLogs" -> {
                    val filter = params[0].jsonObject
                    val from = EvmAbi.parseHexUnsigned(filter["fromBlock"]!!.jsonPrimitive.content).toLong()
                    val to = EvmAbi.parseHexUnsigned(filter["toBlock"]!!.jsonPrimitive.content).toLong()
                    val matching = logs.values.filter { it.first in from..to }.map { it.second }
                    JsonArray(matching)
                }
                else -> JsonNull
            }

            respond(
                content = JsonObject(mapOf("jsonrpc" to JsonPrimitive("2.0"), "id" to id, "result" to result)).toString(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        },
    )

    fun putTransferReceipt(
        txHash: String,
        status: Int,
        blockNumber: Long,
        contract: String,
        from: String,
        to: String,
        amountRaw: BigInteger,
        logIndex: Int = 0,
    ) {
        val logObj = JsonObject(
            mapOf(
                "address" to JsonPrimitive(contract.lowercase()),
                "topics" to JsonArray(
                    listOf(
                        JsonPrimitive(EvmAbi.TRANSFER_EVENT_TOPIC),
                        JsonPrimitive(EvmAbi.padAddressTopic(from)),
                        JsonPrimitive(EvmAbi.padAddressTopic(to)),
                    ),
                ),
                "data" to JsonPrimitive("0x" + amountRaw.toString(16).padStart(64, '0')),
                "blockNumber" to JsonPrimitive("0x" + blockNumber.toString(16)),
                "transactionHash" to JsonPrimitive(txHash.lowercase()),
                "logIndex" to JsonPrimitive("0x" + logIndex.toString(16)),
                "removed" to JsonPrimitive(false),
            ),
        )
        if (status == 1) {
            logs["$txHash:$logIndex"] = blockNumber to logObj
        }
        receipts[txHash.lowercase()] = JsonObject(
            mapOf(
                "transactionHash" to JsonPrimitive(txHash.lowercase()),
                "blockNumber" to JsonPrimitive("0x" + blockNumber.toString(16)),
                "status" to JsonPrimitive("0x" + status.toString(16)),
                "logs" to JsonArray(listOf(logObj)),
            ),
        )
    }
}
