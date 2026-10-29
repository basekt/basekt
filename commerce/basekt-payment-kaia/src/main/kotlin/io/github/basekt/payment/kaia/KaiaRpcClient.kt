package io.github.basekt.payment.kaia

import io.github.basekt.payment.PaymentException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

internal data class RpcLog(
    val address: String,
    val topics: List<String>,
    val data: String,
    val blockNumber: Long,
    val transactionHash: String,
    val logIndex: Int,
    val removed: Boolean,
)

internal data class RpcTransactionReceipt(
    val transactionHash: String,
    val blockNumber: Long?,
    val status: Int?,
    val logs: List<RpcLog>,
)

/**
 * Suspend JSON-RPC 2.0 client over Ktor [HttpClient] with no external EVM SDK dependency.
 */
internal class KaiaRpcClient(
    private val rpcUrl: String,
    private val requestTimeout: Duration,
    private val httpClient: HttpClient,
) {
    private val ids = AtomicLong(0)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun chainId(): Long = EvmAbi.parseHexUnsigned(callString("eth_chainId", JsonArray(emptyList()))).toLong()

    suspend fun blockNumber(): Long = EvmAbi.parseHexUnsigned(callString("eth_blockNumber", JsonArray(emptyList()))).toLong()

    suspend fun nativeBalance(address: String): BigInteger {
        val hex = callString(
            "eth_getBalance",
            JsonArray(listOf(JsonPrimitive(EvmAbi.normalizeAddress(address)), JsonPrimitive("latest"))),
        )
        return EvmAbi.parseHexUnsigned(hex)
    }

    suspend fun tokenBalance(tokenAddress: String, walletAddress: String): BigInteger {
        val callObj = JsonObject(
            mapOf(
                "to" to JsonPrimitive(EvmAbi.normalizeAddress(tokenAddress)),
                "data" to JsonPrimitive(EvmAbi.encodeBalanceOf(walletAddress)),
            ),
        )
        val hex = callString("eth_call", JsonArray(listOf(callObj, JsonPrimitive("latest"))))
        if (hex == "0x" || hex.isBlank()) return BigInteger.ZERO
        return EvmAbi.parseHexUnsigned(hex)
    }

    suspend fun gasPrice(): BigInteger =
        EvmAbi.parseHexUnsigned(callString("eth_gasPrice", JsonArray(emptyList())))

    suspend fun estimateGas(from: String, to: String, data: String): BigInteger {
        val callObj = JsonObject(
            mapOf(
                "from" to JsonPrimitive(EvmAbi.normalizeAddress(from)),
                "to" to JsonPrimitive(EvmAbi.normalizeAddress(to)),
                "data" to JsonPrimitive(data),
            ),
        )
        return EvmAbi.parseHexUnsigned(callString("eth_estimateGas", JsonArray(listOf(callObj))))
    }

    suspend fun transactionCount(address: String): BigInteger {
        val hex = callString(
            "eth_getTransactionCount",
            JsonArray(listOf(JsonPrimitive(EvmAbi.normalizeAddress(address)), JsonPrimitive("pending"))),
        )
        return EvmAbi.parseHexUnsigned(hex)
    }

    suspend fun sendRawTransaction(signedHex: String): String =
        callString("eth_sendRawTransaction", JsonArray(listOf(JsonPrimitive(signedHex))))

    suspend fun getTransactionReceipt(txHash: String): RpcTransactionReceipt? {
        val result = call("eth_getTransactionReceipt", JsonArray(listOf(JsonPrimitive(txHash))))
        if (result is JsonNull) return null
        val obj = result.jsonObject
        val blockHex = obj["blockNumber"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
        val statusHex = obj["status"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
        val logs = obj["logs"]?.takeUnless { it is JsonNull }?.jsonArray.orEmpty().mapNotNull { parseLog(it.jsonObject) }
        return RpcTransactionReceipt(
            transactionHash = obj["transactionHash"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: txHash.lowercase(),
            blockNumber = blockHex?.let { EvmAbi.parseHexUnsigned(it).toLong() },
            status = statusHex?.let { EvmAbi.parseHexUnsigned(it).toInt() },
            logs = logs,
        )
    }

    suspend fun getTransferLogs(
        tokenAddress: String,
        recipientAddress: String,
        fromBlock: Long,
        toBlock: Long,
    ): List<RpcLog> {
        val filter = JsonObject(
            mapOf(
                "fromBlock" to JsonPrimitive(EvmAbi.toHexQuantity(BigInteger.valueOf(fromBlock))),
                "toBlock" to JsonPrimitive(EvmAbi.toHexQuantity(BigInteger.valueOf(toBlock))),
                "address" to JsonPrimitive(EvmAbi.normalizeAddress(tokenAddress)),
                "topics" to JsonArray(
                    listOf(
                        JsonPrimitive(EvmAbi.TRANSFER_EVENT_TOPIC),
                        JsonNull,
                        JsonPrimitive(EvmAbi.padAddressTopic(recipientAddress)),
                    ),
                ),
            ),
        )
        val result = call("eth_getLogs", JsonArray(listOf(filter)))
        if (result is JsonNull) return emptyList()
        return result.jsonArray.mapNotNull { parseLog(it.jsonObject) }
    }

    private fun parseLog(obj: JsonObject): RpcLog? {
        val address = obj["address"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return null
        val txHash = obj["transactionHash"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull?.lowercase() ?: return null
        val blockHex = obj["blockNumber"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull ?: return null
        val logIndexHex = obj["logIndex"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull ?: "0x0"
        val removed = obj["removed"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val topics = obj["topics"]?.takeUnless { it is JsonNull }?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
        val data = obj["data"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull ?: "0x0"
        return RpcLog(
            address = address,
            topics = topics,
            data = data,
            blockNumber = EvmAbi.parseHexUnsigned(blockHex).toLong(),
            transactionHash = txHash,
            logIndex = EvmAbi.parseHexUnsigned(logIndexHex).toInt(),
            removed = removed,
        )
    }

    private suspend fun callString(method: String, params: JsonArray): String {
        val element = call(method, params)
        return element.jsonPrimitive.contentOrNull
            ?: throw PaymentException("Kaia RPC '$method' returned a non-string result: $element")
    }

    private suspend fun call(method: String, params: JsonArray): JsonElement {
        val requestId = ids.incrementAndGet()
        val payload = JsonObject(
            mapOf(
                "jsonrpc" to JsonPrimitive("2.0"),
                "id" to JsonPrimitive(requestId),
                "method" to JsonPrimitive(method),
                "params" to params,
            ),
        ).toString()

        val hasTimeoutPlugin = httpClient.pluginOrNull(HttpTimeout) != null
        val response = try {
            httpClient.post(rpcUrl) {
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                if (hasTimeoutPlugin) {
                    timeout {
                        requestTimeoutMillis = requestTimeout.inWholeMilliseconds
                    }
                }
                setBody(payload)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PaymentException("Kaia RPC '$method' transport error: ${e.message}", e)
        }

        val statusCode = response.status.value
        val responseBody = response.bodyAsText()
        if (statusCode !in 200..299) {
            throw PaymentException("Kaia RPC '$method' failed with HTTP $statusCode.")
        }
        val root = try {
            json.parseToJsonElement(responseBody).jsonObject
        } catch (e: Exception) {
            throw PaymentException("Kaia RPC '$method' returned invalid JSON.", e)
        }
        val error = root["error"]?.takeUnless { it is JsonNull }?.jsonObject
        if (error != null) {
            val code = error["code"]?.jsonPrimitive?.intOrNull
            val message = error["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown RPC error"
            throw PaymentException("Kaia RPC '$method' error${code?.let { " ($it)" }.orEmpty()}: $message")
        }
        return root["result"] ?: JsonNull
    }
}
