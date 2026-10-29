package io.github.basekt.payments

import io.github.basekt.data.exposed.ExposedDataSource
import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.payment.PaymentAmount
import io.github.basekt.payment.PayoutRequest
import io.github.basekt.payment.kaia.EvmAbi
import io.github.basekt.payment.kaia.KaiaSettings
import io.github.basekt.payment.kaia.KaiaUsdtPaymentProvider
import io.github.basekt.payment.lemonsqueezy.LemonSqueezyPaymentProvider
import io.github.basekt.payment.lemonsqueezy.LemonSqueezySettings
import io.github.basekt.payment.memory.InMemoryPaymentProvider
import io.github.basekt.payment.paymentwall.PaymentwallPaymentProvider
import io.github.basekt.payment.paymentwall.PaymentwallSettings
import io.github.basekt.payment.paymentwall.PaymentwallSignatures
import io.github.basekt.payment.paymongo.PayMongoFlow
import io.github.basekt.payment.paymongo.PayMongoPaymentProvider
import io.github.basekt.payment.paymongo.PayMongoSettings
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
import io.github.basekt.schema.schema
import io.github.basekt.server.ApiException
import io.github.basekt.server.BaseKt
import io.github.basekt.server.ErrorCode
import io.github.basekt.server.Identity
import io.github.basekt.server.IdentityResolver
import io.github.ronjunevaldoz.paymongo.PayMongo
import io.github.ronjunevaldoz.paymongo.serialization.PayMongoJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.http.headersOf
import io.ktor.http.path
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import java.math.BigInteger
import java.util.Collections
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Schema 1: A neighborhood bookshop selling signed print `editions` priced in `PHP`, `USD` or `USDT`.
 */
private val bookshopSchema = schema {
    paymentLedger(readers = setOf("admin"))
    resource("editions") {
        string("title") { required() }
        decimal("price") { required() }
        enum("currency", listOf("PHP", "USD", "USDT")) { required() }
        string("readerEmail")
        enum("state", listOf("reserved", "paid", "failed", "refunded")) { required() }
        string("curatorNote") { hidden() }
        permissions { read(everyone) }
        payable {
            amount("price")
            currency("currency")
            description("title")
            buyerEmail("readerEmail")
            statusField("state", paid = "paid", failed = "failed", refunded = "refunded")
        }
    }
}

/**
 * Schema 2: A ceramic studio taking `registrations` for wheel-throwing workshops settled in `USDT`.
 */
private val ceramicStudioSchema = schema {
    paymentLedger()
    resource("registrations") {
        string("workshopTitle") { required() }
        int("feeUsdt") { required() }
        string("wallet")
        enum("enrollment", listOf("pending", "confirmed", "refunded")) { required() }
        permissions { read(everyone) }
        payable {
            amount("feeUsdt")
            fixedCurrency("USDT")
            description("workshopTitle")
            payerAddress("wallet")
            statusField("enrollment", paid = "confirmed", refunded = "refunded")
        }
    }
}

private class AdjustableClock(var current: Instant = Instant.parse("2026-06-01T12:00:00Z")) : Clock {
    override fun now(): Instant = current
    fun advance(by: Duration) {
        current += by
    }
}

class BaseKtPaymentsTest {
    private val treasury = "0x1111111111111111111111111111111111111111"
    private val readerWallet = "0x2222222222222222222222222222222222222222"
    private val webhookSecret = "whsec_bookshop_test"

    private val callers = IdentityResolver { call ->
        call.request.headers["X-Caller"]?.let { raw ->
            Identity(
                id = raw.substringBefore(':'),
                roles = raw.substringAfter(':', "").split(',').filter { it.isNotEmpty() }.toSet(),
            )
        }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun hmacSha256Hex(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.encodeToByteArray(), "HmacSHA256"))
        return mac.doFinal(payload.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun paymongoSignature(secret: String, timestamp: Long, rawBody: String): String {
        val hex = hmacSha256Hex(secret, "$timestamp.$rawBody")
        return "t=$timestamp,te=$hex,li="
    }

    @Test
    fun `bookshop settles PHP editions through PayMongo webhooks, deduplicates redeliveries and restricts refunds`() = testApplication {
        val source = InMemoryDataSource()
        val clock = AdjustableClock()
        val editionId = runBlocking {
            val created = source.create(
                bookshopSchema.resource("editions")!!,
                mapOf(
                    "title" to Value.Text("First Printing — Archipelago Atlas"),
                    "price" to Value.Decimal.of("450.00"),
                    "currency" to Value.Text("PHP"),
                    "state" to Value.Text("reserved"),
                    "curatorNote" to Value.Text("Signed on flyleaf"),
                ),
            )
            (created[Resource.ID] as Value.Text).value
        }

        val mockPayMongo = MockEngine { request ->
            val path = request.url.encodedPath
            val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            when {
                path.endsWith("/payment_links") -> respond(
                    content = """{"data":{"id":"link_book_1","amount":45000,"currency":"PHP","description":"First Printing — Archipelago Atlas","status":"unpaid","livemode":false,"url":"https://pm.link/link_book_1","reference_number":"REFBOOK1","created_at":"2026-10-30T00:00:00Z","updated_at":"2026-10-30T00:00:00Z"}}""",
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders,
                )
                path.endsWith("/refunds") -> respond(
                    content = """{"data":{"id":"rfnd_book_1","amount":45000,"currency":"PHP","status":"succeeded","payment_id":"pay_book_1","reason":"requested_by_customer","livemode":false,"created_at":"2026-10-30T00:00:00Z","updated_at":"2026-10-30T00:00:00Z"}}""",
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders,
                )
                else -> error("Unexpected PayMongo path: $path")
            }
        }
        val paymongoHttp = HttpClient(mockPayMongo) {
            install(ContentNegotiation) { json(PayMongoJson) }
            defaultRequest {
                url {
                    protocol = URLProtocol.HTTPS
                    host = "api.paymongo.com"
                    path("v1/")
                }
                contentType(ContentType.Application.Json)
            }
        }

        val paymongo = PayMongoPaymentProvider(
            settings = PayMongoSettings(
                secretKey = "sk_test_bookshop",
                webhookSecretKey = webhookSecret,
                flow = PayMongoFlow.PAYMENT_LINK,
                clock = clock,
            ),
            client = PayMongo(PayMongo.Config(secretKey = "sk_test_bookshop"), paymongoHttp),
        )

        val settlements = mutableListOf<PaymentSettlement>()
        application {
            install(BaseKt) {
                dataSource = source
                schema = bookshopSchema
                identities = callers
                this.clock = clock
            }
            install(BaseKtPayments) {
                provider(paymongo)
                onSettled { settlements += it }
            }
        }

        val providers = Json.parseToJsonElement(client.get("/payments/providers").bodyAsText()).jsonArray
        assertEquals(1, providers.size)
        assertEquals("paymongo", providers[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertTrue(providers[0].jsonObject["webhook"]!!.jsonPrimitive.boolean)
        assertTrue(providers[0].jsonObject["refundable"]!!.jsonPrimitive.boolean)

        // Create checkout session — price comes strictly from server record (450.00 PHP -> 45000 centavos)
        val createdResp = client.post("/payments/editions/$editionId") {
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"paymongo"}""")
        }
        assertEquals(HttpStatusCode.Created, createdResp.status)
        val session = createdResp.json()
        val sessionId = session["id"]!!.jsonPrimitive.content
        assertEquals("450", session["amount"]!!.jsonPrimitive.content)
        assertEquals("45000", session["minorUnits"]!!.jsonPrimitive.content)
        assertEquals("https://pm.link/link_book_1", session["checkoutUrl"]!!.jsonPrimitive.content)

        // Tampered webhook signature is refused with 400 bad_request
        val webhookPayload = """
            {
              "data": {
                "id": "evt_book_paid_1",
                "type": "event",
                "attributes": {
                  "type": "link.payment.paid",
                  "created_at": ${clock.now().epochSeconds},
                  "data": {
                    "id": "link_book_1",
                    "type": "link",
                    "attributes": {
                      "remarks": "$sessionId",
                      "payments": [
                        {
                          "id": "pay_book_1",
                          "type": "payment",
                          "attributes": { "amount": 45000, "currency": "PHP", "status": "paid" }
                        }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val badSig = client.post("/payments/webhooks/paymongo") {
            header("Paymongo-Signature", "t=${clock.now().epochSeconds},te=deadbeef,li=")
            setBody(webhookPayload)
        }
        assertEquals(HttpStatusCode.BadRequest, badSig.status)
        assertEquals("bad_request", badSig.json()["error"]!!.jsonPrimitive.content)

        // Valid signed webhook settles both the ledger session and the target edition record
        val sig = paymongoSignature(webhookSecret, clock.now().epochSeconds, webhookPayload)
        val okHook = client.post("/payments/webhooks/paymongo") {
            header("Paymongo-Signature", sig)
            setBody(webhookPayload)
        }
        assertEquals(HttpStatusCode.OK, okHook.status)
        assertFalse(okHook.json()["duplicate"]!!.jsonPrimitive.boolean)
        assertEquals("succeeded", okHook.json()["status"]!!.jsonPrimitive.content)
        assertEquals(1, settlements.size)

        val editionAfterPay = client.get("/api/editions/$editionId").json()
        assertEquals("paid", editionAfterPay["state"]!!.jsonPrimitive.content)

        // Duplicate webhook redelivery is idempotently ignored
        val duplicateHook = client.post("/payments/webhooks/paymongo") {
            header("Paymongo-Signature", sig)
            setBody(webhookPayload)
        }
        assertEquals(HttpStatusCode.OK, duplicateHook.status)
        assertTrue(duplicateHook.json()["duplicate"]!!.jsonPrimitive.boolean)
        assertEquals(1, settlements.size)

        // Creating a second session on an already-paid edition is refused with 409 conflict
        val secondAttempt = client.post("/payments/editions/$editionId") {
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"paymongo"}""")
        }
        assertEquals(HttpStatusCode.Conflict, secondAttempt.status)

        // Refund requires admin role
        assertEquals(HttpStatusCode.Unauthorized, client.post("/payments/sessions/$sessionId/refund").status)
        assertEquals(
            HttpStatusCode.Forbidden,
            client.post("/payments/sessions/$sessionId/refund") { header("X-Caller", "reader1:member") }.status,
        )
        val refunded = client.post("/payments/sessions/$sessionId/refund") {
            header("X-Caller", "curator:admin")
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"requested_by_customer"}""")
        }
        assertEquals(HttpStatusCode.OK, refunded.status)
        assertEquals("refunded", refunded.json()["status"]!!.jsonPrimitive.content)
        assertEquals("refunded", client.get("/api/editions/$editionId").json()["state"]!!.jsonPrimitive.content)
        assertEquals(2, settlements.size)
    }

    @Test
    fun `bookshop settles USD editions via Paymentwall GET and POST pingbacks with plain-text OK and via Lemon Squeezy JSON_API`() = testApplication {
        val source = InMemoryDataSource()
        val pwSecret = "pw_live_secret_bookshop"
        val pwTestSecret = "pw_test_secret_bookshop"
        val lsqSecret = "lsq_whsec_bookshop"

        val (pwEditionId, lsqEditionId) = runBlocking {
            val res = bookshopSchema.resource("editions")!!
            val e1 = source.create(
                res,
                mapOf(
                    "title" to Value.Text("Botanical Plates Folio"),
                    "price" to Value.Decimal.of("19.00"),
                    "currency" to Value.Text("USD"),
                    "readerEmail" to Value.Text("folio.buyer@example.com"),
                    "state" to Value.Text("reserved"),
                ),
            )
            val e2 = source.create(
                res,
                mapOf(
                    "title" to Value.Text("Cartographic Essay Collection"),
                    "price" to Value.Decimal.of("34.50"),
                    "currency" to Value.Text("USD"),
                    "readerEmail" to Value.Text("essay.buyer@example.com"),
                    "state" to Value.Text("reserved"),
                ),
            )
            (e1[Resource.ID] as Value.Text).value to (e2[Resource.ID] as Value.Text).value
        }

        var capturedLsqCheckoutRequest: JsonObject? = null
        val lsqHttp = HttpClient(
            MockEngine { request ->
                val raw = request.body.toByteArray().decodeToString()
                capturedLsqCheckoutRequest = Json.parseToJsonElement(raw).jsonObject
                respond(
                    content = """{"data":{"id":"chk_lsq_book_1","type":"checkouts","attributes":{"url":"https://checkout.lemonsqueezy.com/checkout/buy/chk_lsq_book_1"}}}""",
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json"),
                )
            },
        )

        val paymentwall = PaymentwallPaymentProvider(
            PaymentwallSettings(
                projectKey = "pw_proj_bookshop",
                secretKey = pwSecret,
                testSecretKey = pwTestSecret,
                defaultSuccessUrl = "https://bookshop.example/thanks",
            ),
        )
        val lemonSqueezy = LemonSqueezyPaymentProvider(
            settings = LemonSqueezySettings(
                apiKey = "lsq_key_bookshop",
                storeId = "7701",
                webhookSecret = lsqSecret,
                defaultVariantId = "9901",
            ),
            httpClient = lsqHttp,
        )

        val settlements = mutableListOf<PaymentSettlement>()
        application {
            install(BaseKt) {
                dataSource = source
                schema = bookshopSchema
                identities = callers
            }
            install(BaseKtPayments) {
                clientIp = { call -> call.request.headers["X-Test-Client-Ip"] }
                provider(paymentwall)
                provider(lemonSqueezy)
                onSettled { settlements += it }
            }
        }

        // --- Part A: Paymentwall Digital Goods + GET & POST pingbacks ---
        val pwCreated = client.post("/payments/editions/$pwEditionId") {
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"paymentwall"}""")
        }.json()
        val pwSessionId = pwCreated["id"]!!.jsonPrimitive.content
        val pwUrl = Url(pwCreated["checkoutUrl"]!!.jsonPrimitive.content)
        assertEquals("folio.buyer@example.com", pwUrl.parameters["email"])
        assertEquals("19.00", pwUrl.parameters["amount"])

        // 1. Spoofed sender IP outside Paymentwall's allowlist -> 403 Forbidden (NOT "OK")
        val pingbackParams = mapOf(
            "uid" to pwSessionId,
            "goodsid" to pwSessionId,
            "slength" to "",
            "speriod" to "",
            "type" to "0",
            "ref" to "pw_ref_book_1",
            "sign_version" to "3",
        )
        val signedPingback = pingbackParams + ("sign" to PaymentwallSignatures.signV3(pingbackParams, pwSecret))
        val query = signedPingback.entries.map { it.key to it.value }.formUrlEncode()

        val blockedIpResp = client.get("/payments/webhooks/paymentwall?$query") {
            header("X-Test-Client-Ip", "203.0.113.99")
        }
        assertEquals(HttpStatusCode.Forbidden, blockedIpResp.status)
        assertEquals("forbidden", blockedIpResp.json()["error"]!!.jsonPrimitive.content)

        // 2. Dashboard "Test pingback" probe (empty goodsid= & ref= signed with testSecretKey) -> 200 plain-text "OK"
        val probeParams = mapOf(
            "uid" to "goodsid",
            "goodsid" to "",
            "slength" to "",
            "speriod" to "",
            "type" to "0",
            "ref" to "",
            "is_test" to "1",
            "sign_version" to "3",
        ).let { it + ("sign" to PaymentwallSignatures.signV3(it, pwTestSecret)) }
        val probeResp = client.get("/payments/webhooks/paymentwall?${probeParams.entries.map { it.key to it.value }.formUrlEncode()}") {
            header("X-Test-Client-Ip", "174.36.92.186")
        }
        assertEquals(HttpStatusCode.OK, probeResp.status)
        assertEquals("OK", probeResp.bodyAsText())
        assertEquals(0, settlements.size)

        // 3. Genuine GET pingback from 216.127.71.0/24 settles edition and responds with plain-text "OK"
        val okGetResp = client.get("/payments/webhooks/paymentwall?$query") {
            header("X-Test-Client-Ip", "216.127.71.19")
        }
        assertEquals(HttpStatusCode.OK, okGetResp.status)
        assertEquals(ContentType.Text.Plain, okGetResp.contentType()?.withoutParameters())
        assertEquals("OK", okGetResp.bodyAsText())
        assertEquals("paid", client.get("/api/editions/$pwEditionId").json()["state"]!!.jsonPrimitive.content)
        assertEquals(1, settlements.size)

        // 4. Duplicate redelivery of same (ref, type) still answers "OK" without double-firing onSettled
        val dupGetResp = client.get("/payments/webhooks/paymentwall?$query") {
            header("X-Test-Client-Ip", "216.127.71.19")
        }
        assertEquals(HttpStatusCode.OK, dupGetResp.status)
        assertEquals("OK", dupGetResp.bodyAsText())
        assertEquals(1, settlements.size)

        // 5. POST application/x-www-form-urlencoded negative pingback (type=2) marks edition refunded
        val refundParams = pingbackParams + ("type" to "2")
        val signedRefundForm = (refundParams + ("sign" to PaymentwallSignatures.signV3(refundParams, pwSecret)))
            .entries.map { it.key to it.value }.formUrlEncode()
        val postRefundResp = client.post("/payments/webhooks/paymentwall") {
            header("X-Test-Client-Ip", "174.36.96.66")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(signedRefundForm)
        }
        assertEquals(HttpStatusCode.OK, postRefundResp.status)
        assertEquals("OK", postRefundResp.bodyAsText())
        assertEquals("refunded", client.get("/api/editions/$pwEditionId").json()["state"]!!.jsonPrimitive.content)
        assertEquals(2, settlements.size)

        // --- Part B: Lemon Squeezy JSON:API checkout + X-Signature webhook ---
        val lsqCreated = client.post("/payments/editions/$lsqEditionId") {
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"lemonsqueezy"}""")
        }.json()
        val lsqSessionId = lsqCreated["id"]!!.jsonPrimitive.content
        assertEquals("https://checkout.lemonsqueezy.com/checkout/buy/chk_lsq_book_1", lsqCreated["checkoutUrl"]!!.jsonPrimitive.content)

        val lsqAttrs = capturedLsqCheckoutRequest!!["data"]!!.jsonObject["attributes"]!!.jsonObject
        assertEquals(3450, lsqAttrs["custom_price"]!!.jsonPrimitive.int)
        assertEquals("essay.buyer@example.com", lsqAttrs["checkout_data"]!!.jsonObject["email"]!!.jsonPrimitive.content)
        assertEquals(
            lsqSessionId,
            lsqAttrs["checkout_data"]!!.jsonObject["custom"]!!.jsonObject["reference_id"]!!.jsonPrimitive.content,
        )

        val lsqOrderPaid = """
            {
              "meta": {
                "event_name": "order_created",
                "custom_data": { "reference_id": "$lsqSessionId" }
              },
              "data": {
                "id": "ord_lsq_900",
                "type": "orders",
                "attributes": {
                  "store_id": 7701,
                  "status": "paid",
                  "currency": "USD",
                  "total": 3450,
                  "test_mode": false,
                  "updated_at": "2026-10-30T03:00:00Z"
                }
              }
            }
        """.trimIndent()
        val lsqHookResp = client.post("/payments/webhooks/lemonsqueezy") {
            header("X-Signature", hmacSha256Hex(lsqSecret, lsqOrderPaid))
            contentType(ContentType.Application.Json)
            setBody(lsqOrderPaid)
        }
        assertEquals(HttpStatusCode.OK, lsqHookResp.status)
        assertEquals("succeeded", lsqHookResp.json()["status"]!!.jsonPrimitive.content)
        assertEquals("paid", client.get("/api/editions/$lsqEditionId").json()["state"]!!.jsonPrimitive.content)
        assertEquals(3, settlements.size)
    }

    @Test
    fun `bookshop confirms Kaia USDT transfers on-chain over Ktor HttpClient and blocks cross-session txHash replay and fake contracts`() = testApplication {
        val validTx = "0x" + "a".repeat(64)
        val spoofedTx = "0x" + "b".repeat(64)
        val amountHex = "0x" + BigInteger.valueOf(15_000_000L).toString(16).padStart(64, '0')
        val fromTopic = EvmAbi.padAddressTopic(readerWallet)
        val toTopic = EvmAbi.padAddressTopic(treasury)

        val kaiaHttp = HttpClient(
            MockEngine { request ->
                val req = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                val id = req["id"]!!
                val method = req["method"]!!.jsonPrimitive.content
                val resultJson = when (method) {
                    "eth_chainId" -> "\"0x2019\"" // 8217 Kaia Mainnet
                    "eth_blockNumber" -> "\"0x64\"" // block 100
                    "eth_getTransactionReceipt" -> {
                        val tx = req["params"]!!.jsonArray[0].jsonPrimitive.content.lowercase()
                        when (tx) {
                            validTx -> """
                                {
                                  "transactionHash": "$validTx",
                                  "blockNumber": "0x60",
                                  "status": "0x1",
                                  "logs": [{
                                    "address": "${EvmAbi.KAIA_MAINNET_USDT}",
                                    "topics": ["${EvmAbi.TRANSFER_EVENT_TOPIC}", "$fromTopic", "$toTopic"],
                                    "data": "$amountHex",
                                    "blockNumber": "0x60",
                                    "transactionHash": "$validTx",
                                    "logIndex": "0x0",
                                    "removed": false
                                  }]
                                }
                            """.trimIndent()
                            spoofedTx -> """
                                {
                                  "transactionHash": "$spoofedTx",
                                  "blockNumber": "0x60",
                                  "status": "0x1",
                                  "logs": [{
                                    "address": "0x9999999999999999999999999999999999999999",
                                    "topics": ["${EvmAbi.TRANSFER_EVENT_TOPIC}", "$fromTopic", "$toTopic"],
                                    "data": "$amountHex",
                                    "blockNumber": "0x60",
                                    "transactionHash": "$spoofedTx",
                                    "logIndex": "0x0",
                                    "removed": false
                                  }]
                                }
                            """.trimIndent()
                            else -> "null"
                        }
                    }
                    else -> error("Unexpected RPC method: $method")
                }
                respond(
                    content = """{"jsonrpc":"2.0","id":$id,"result":$resultJson}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        )

        val source = InMemoryDataSource()
        val (edition1, edition2) = runBlocking {
            val res = bookshopSchema.resource("editions")!!
            val e1 = source.create(
                res,
                mapOf(
                    "title" to Value.Text("Monograph Vol. I"),
                    "price" to Value.Decimal.of("15.00"),
                    "currency" to Value.Text("USDT"),
                    "state" to Value.Text("reserved"),
                ),
            )
            val e2 = source.create(
                res,
                mapOf(
                    "title" to Value.Text("Monograph Vol. II"),
                    "price" to Value.Decimal.of("15.00"),
                    "currency" to Value.Text("USDT"),
                    "state" to Value.Text("reserved"),
                ),
            )
            (e1[Resource.ID] as Value.Text).value to (e2[Resource.ID] as Value.Text).value
        }

        val kaia = KaiaUsdtPaymentProvider(
            settings = KaiaSettings.mainnet(recipientAddress = treasury),
            httpClient = kaiaHttp,
        )

        application {
            install(BaseKt) {
                dataSource = source
                schema = bookshopSchema
                identities = callers
            }
            install(BaseKtPayments) {
                provider(kaia)
            }
        }

        val s1 = client.post("/payments/editions/$edition1") {
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"kaia-usdt"}""")
        }.json()
        val s2 = client.post("/payments/editions/$edition2") {
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"kaia-usdt"}""")
        }.json()

        val s1Id = s1["id"]!!.jsonPrimitive.content
        val s2Id = s2["id"]!!.jsonPrimitive.content
        val onChain = s1["onChain"]!!.jsonObject
        assertEquals("8217", onChain["chainId"]!!.jsonPrimitive.content)
        assertEquals(EvmAbi.KAIA_MAINNET_USDT, onChain["tokenAddress"]!!.jsonPrimitive.content)
        assertContains(onChain["eip681Uri"]!!.jsonPrimitive.content, "uint256=15000000")

        // Fake ERC-20 token contract log is rejected (422 rejected)
        val spoofResp = client.post("/payments/sessions/$s1Id/confirm") {
            contentType(ContentType.Application.Json)
            setBody("""{"txHash":"$spoofedTx"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, spoofResp.status)
        assertEquals("rejected", spoofResp.json()["error"]!!.jsonPrimitive.content)

        // Valid USDT transfer settles edition 1
        val confirm1 = client.post("/payments/sessions/$s1Id/confirm") {
            contentType(ContentType.Application.Json)
            setBody("""{"txHash":"$validTx"}""")
        }
        assertEquals(HttpStatusCode.OK, confirm1.status)
        assertEquals("succeeded", confirm1.json()["status"]!!.jsonPrimitive.content)
        assertEquals("paid", client.get("/api/editions/$edition1").json()["state"]!!.jsonPrimitive.content)

        // Idempotent retry on the SAME session succeeds cleanly
        val retrySame = client.post("/payments/sessions/$s1Id/confirm") {
            contentType(ContentType.Application.Json)
            setBody("""{"txHash":"$validTx"}""")
        }
        assertEquals(HttpStatusCode.OK, retrySame.status)

        // Cross-session replay attack on edition 2 with the same txHash is blocked with 409 conflict!
        val replayOther = client.post("/payments/sessions/$s2Id/confirm") {
            contentType(ContentType.Application.Json)
            setBody("""{"txHash":"$validTx"}""")
        }
        assertEquals(HttpStatusCode.Conflict, replayOther.status)
        assertEquals("conflict", replayOther.json()["error"]!!.jsonPrimitive.content)
        assertEquals("reserved", client.get("/api/editions/$edition2").json()["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ceramic studio reconciles incoming USDT logs in the background, expires stale sessions and executes payouts`() = testApplication {
        val source = InMemoryDataSource()
        val clock = AdjustableClock()
        val potterWallet = "0x3333333333333333333333333333333333333333"
        val instructorWallet = "0x4444444444444444444444444444444444444444"

        val (reg1, reg2) = runBlocking {
            val res = ceramicStudioSchema.resource("registrations")!!
            val r1 = source.create(
                res,
                mapOf(
                    "workshopTitle" to Value.Text("Saturday Stoneware Glazing"),
                    "feeUsdt" to Value.Integer(25),
                    "wallet" to Value.Text(potterWallet),
                    "enrollment" to Value.Text("pending"),
                ),
            )
            val r2 = source.create(
                res,
                mapOf(
                    "workshopTitle" to Value.Text("Sunday Kiln Firing"),
                    "feeUsdt" to Value.Integer(40),
                    "wallet" to Value.Text(potterWallet),
                    "enrollment" to Value.Text("pending"),
                ),
            )
            (r1[Resource.ID] as Value.Text).value to (r2[Resource.ID] as Value.Text).value
        }

        val memoryUsdt = InMemoryPaymentProvider(
            id = "kaia-usdt",
            supportedCurrencies = setOf("USDT"),
            onChainMode = true,
            recipientAddress = treasury,
        )

        lateinit var runtime: PaymentsRuntime
        application {
            install(BaseKt) {
                dataSource = source
                schema = ceramicStudioSchema
                this.clock = clock
            }
            install(BaseKtPayments) {
                sessionTtl = 15.minutes
                provider(memoryUsdt, startFromBlock = 1L)
            }
            runtime = baseKtPayments
        }

        val sess1 = client.post("/payments/registrations/$reg1") {
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"kaia-usdt"}""")
        }.json()
        val sess2 = client.post("/payments/registrations/$reg2") {
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"kaia-usdt"}""")
        }.json()
        val sess1Id = sess1["id"]!!.jsonPrimitive.content
        val sess2Id = sess2["id"]!!.jsonPrimitive.content

        // Potter transfers 25 USDT from potterWallet without calling /confirm; background reconciler matches & settles it
        memoryUsdt.recordOnChainTransfer(
            amount = PaymentAmount.ofDecimal("25", "USDT"),
            fromAddress = potterWallet,
        )
        val settledCount = runtime.reconcileOnChainTransfers()
        assertEquals(1, settledCount)
        assertEquals("succeeded", client.get("/payments/sessions/$sess1Id").json()["status"]!!.jsonPrimitive.content)
        assertEquals("confirmed", client.get("/api/registrations/$reg1").json()["enrollment"]!!.jsonPrimitive.content)

        // Advance clock past 15-minute TTL: sess2 expires and refuses late confirmation
        clock.advance(20.minutes)
        assertEquals("expired", client.get("/payments/sessions/$sess2Id").json()["status"]!!.jsonPrimitive.content)
        val lateTx = memoryUsdt.recordOnChainTransfer(
            amount = PaymentAmount.ofDecimal("40", "USDT"),
            fromAddress = potterWallet,
        )
        val lateConfirm = client.post("/payments/sessions/$sess2Id/confirm") {
            contentType(ContentType.Application.Json)
            setBody("""{"txHash":"$lateTx"}""")
        }
        assertEquals(HttpStatusCode.Conflict, lateConfirm.status)

        // Outbound instructor stipend payout & balance inspection via runtime
        val beforeBalance = runtime.balance("kaia-usdt")
        assertEquals("1000000", beforeBalance.tokenBalance.toDecimalString())
        val payoutReceipt = runtime.payout(
            "kaia-usdt",
            PayoutRequest(
                recipientAddress = instructorWallet,
                amount = PaymentAmount.ofDecimal("12.5", "USDT"),
                referenceId = "stipend-1",
            ),
        )
        assertEquals(instructorWallet, payoutReceipt.recipientAddress)
        assertEquals("999987.5", runtime.balance("kaia-usdt").tokenBalance.toDecimalString())

        // Insufficient gas or balance translates to ApiException(ErrorCode.REJECTED)
        memoryUsdt.setWalletBalances(tokenRaw = BigInteger.ONE, nativeGasRaw = BigInteger.ZERO)
        val rejected = assertFailsWith<ApiException> {
            runtime.payout(
                "kaia-usdt",
                PayoutRequest(
                    recipientAddress = instructorWallet,
                    amount = PaymentAmount.ofDecimal("5", "USDT"),
                ),
            )
        }
        assertEquals(ErrorCode.REJECTED, rejected.error.error)
    }

    @Test
    fun `startup validates both paymentLedger and payable resource definitions at once`() {
        val broken = schema {
            resource("payments") {
                string("provider")
                permissions { create("member") }
            }
            resource("paymentReceipts") {
                keyedBy("provider", "eventKey")
                string("provider")
                string("eventKey")
            }
            resource("editions") {
                string("title")
                string("secret") { hidden() }
                enum("state", listOf("open", "closed"))
                payable {
                    amount("title")
                    fixedCurrency("bad!")
                    description("secret")
                    buyerEmail("secret")
                    statusField("state", paid = "paid")
                }
            }
        }

        val err = assertFailsWith<IllegalArgumentException> {
            testApplication {
                application {
                    install(BaseKt) {
                        dataSource = InMemoryDataSource()
                        schema = broken
                    }
                    install(BaseKtPayments) {
                        provider(InMemoryPaymentProvider())
                    }
                }
                startApplication()
            }
        }
        val msg = err.message.orEmpty()
        assertContains(msg, "callers may create entries in 'payments'")
        assertContains(msg, "'paymentReceipts' must use naturalId()")
        assertContains(msg, "amount 'title' is a string")
        assertContains(msg, "fixedCurrency 'BAD!'")
        assertContains(msg, "description 'secret' is hidden")
        assertContains(msg, "buyerEmail 'secret' is hidden")
        assertContains(msg, "paid value 'paid' is not one of 'state''s enum values")
    }

    @Test
    fun `ExposedDataSource SQL stores 6-decimal Kaia USDT and long Lemon Squeezy event keys and prevents concurrent replay`() = testApplication {
        val database = Database.connect(
            url = "jdbc:h2:mem:basekt-payments-${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        val source = ExposedDataSource(database, bookshopSchema)
        val validTx = "0x" + "c".repeat(64)
        val amountHex = "0x" + BigInteger.valueOf(15_123_456L).toString(16).padStart(64, '0')
        val fromTopic = EvmAbi.padAddressTopic(readerWallet)
        val toTopic = EvmAbi.padAddressTopic(treasury)
        val lsqSecret = "lsq_sql_whsec"

        val (usdtEdition1, usdtEdition2, lsqEdition) = runBlocking {
            source.createTables()
            val res = bookshopSchema.resource("editions")!!
            val u1 = source.create(
                res,
                mapOf(
                    "title" to Value.Text("SQL Monograph Vol. I"),
                    "price" to Value.Decimal.of("15.123456"),
                    "currency" to Value.Text("USDT"),
                    "state" to Value.Text("reserved"),
                ),
            )
            val u2 = source.create(
                res,
                mapOf(
                    "title" to Value.Text("SQL Monograph Vol. II"),
                    "price" to Value.Decimal.of("15.123456"),
                    "currency" to Value.Text("USDT"),
                    "state" to Value.Text("reserved"),
                ),
            )
            val l1 = source.create(
                res,
                mapOf(
                    "title" to Value.Text("SQL Archival Print"),
                    "price" to Value.Decimal.of("29.99"),
                    "currency" to Value.Text("USD"),
                    "readerEmail" to Value.Text("sql.reader@example.com"),
                    "state" to Value.Text("reserved"),
                ),
            )
            Triple(
                (u1[Resource.ID] as Value.Text).value,
                (u2[Resource.ID] as Value.Text).value,
                (l1[Resource.ID] as Value.Text).value,
            )
        }

        val kaiaHttp = HttpClient(
            MockEngine { request ->
                val req = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                val id = req["id"]!!
                val resultJson = when (req["method"]!!.jsonPrimitive.content) {
                    "eth_chainId" -> "\"0x2019\""
                    "eth_blockNumber" -> "\"0x80\""
                    "eth_getTransactionReceipt" -> """
                        {
                          "transactionHash": "$validTx",
                          "blockNumber": "0x70",
                          "status": "0x1",
                          "logs": [{
                            "address": "${EvmAbi.KAIA_MAINNET_USDT}",
                            "topics": ["${EvmAbi.TRANSFER_EVENT_TOPIC}", "$fromTopic", "$toTopic"],
                            "data": "$amountHex",
                            "blockNumber": "0x70",
                            "transactionHash": "$validTx",
                            "logIndex": "0x0",
                            "removed": false
                          }]
                        }
                    """.trimIndent()
                    else -> error("Unexpected RPC method")
                }
                respond(
                    content = """{"jsonrpc":"2.0","id":$id,"result":$resultJson}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        )
        val lsqHttp = HttpClient(
            MockEngine {
                respond(
                    content = """{"data":{"id":"chk_sql_1","type":"checkouts","attributes":{"url":"https://checkout.lemonsqueezy.com/checkout/buy/chk_sql_1"}}}""",
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json"),
                )
            },
        )

        val settlements = Collections.synchronizedList(mutableListOf<PaymentSettlement>())
        lateinit var runtime: PaymentsRuntime
        application {
            install(BaseKt) {
                dataSource = source
                schema = bookshopSchema
                identities = callers
            }
            install(BaseKtPayments) {
                provider(KaiaUsdtPaymentProvider(KaiaSettings.mainnet(recipientAddress = treasury), kaiaHttp))
                provider(
                    LemonSqueezyPaymentProvider(
                        settings = LemonSqueezySettings(
                            apiKey = "lsq_sql_key",
                            storeId = "7701",
                            webhookSecret = lsqSecret,
                            defaultVariantId = "9901",
                        ),
                        httpClient = lsqHttp,
                    ),
                )
                onSettled { settlements += it }
            }
            runtime = baseKtPayments
        }

        val s1Id = client.post("/payments/editions/$usdtEdition1") {
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"kaia-usdt"}""")
        }.json()["id"]!!.jsonPrimitive.content
        val s2Id = client.post("/payments/editions/$usdtEdition2") {
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"kaia-usdt"}""")
        }.json()["id"]!!.jsonPrimitive.content

        // 1. Concurrent racing confirmOnChain for the SAME 83-char <chainId>:<txHash>:<logIndex> key on SQL VARCHAR(64):
        //    exactly ONE session settles and the other fails with CONFLICT!
        val raceOutcomes = runBlocking {
            withContext(Dispatchers.Default) {
                listOf(s1Id, s2Id).map { targetSession ->
                    async { runCatching { runtime.confirmOnChain(targetSession, validTx) } }
                }.awaitAll()
            }
        }
        assertEquals(1, raceOutcomes.count { it.isSuccess })
        assertEquals(1, raceOutcomes.count { (it.exceptionOrNull() as? ApiException)?.error?.error == ErrorCode.CONFLICT })
        val winningView = raceOutcomes.first { it.isSuccess }.getOrThrow()
        assertEquals("15.123456", winningView.amount)
        assertEquals("15123456", winningView.minorUnits)

        // 2. Long Lemon Squeezy event key (> 75 chars) + 6 concurrent webhook redeliveries on SQL:
        val lsqSessionId = client.post("/payments/editions/$lsqEdition") {
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"lemonsqueezy"}""")
        }.json()["id"]!!.jsonPrimitive.content

        val lsqWebhookJson = """
            {
              "meta": {
                "event_name": "subscription_payment_success",
                "custom_data": { "reference_id": "$lsqSessionId" }
              },
              "data": {
                "id": "subscription_invoice_long_identifier_9876543210",
                "type": "subscription-invoices",
                "attributes": {
                  "store_id": 7701,
                  "status": "paid",
                  "currency": "USD",
                  "total": 2999,
                  "test_mode": false,
                  "updated_at": "2026-10-30T03:45:12.987654Z"
                }
              }
            }
        """.trimIndent()
        val lsqHeaders = mapOf("X-Signature" to hmacSha256Hex(lsqSecret, lsqWebhookJson))

        val hookResults = runBlocking {
            withContext(Dispatchers.Default) {
                (1..6).map {
                    async { runtime.handleWebhook("lemonsqueezy", lsqHeaders, lsqWebhookJson) }
                }.awaitAll()
            }
        }
        assertEquals(1, hookResults.count { !it.duplicate })
        assertEquals(5, hookResults.count { it.duplicate })
        assertEquals(2, settlements.size)
        assertEquals("paid", client.get("/api/editions/$lsqEdition").json()["state"]!!.jsonPrimitive.content)

        // Admin can inspect full un-truncated eventKey values in /api/paymentReceipts while IDs fit VARCHAR(64)
        val receiptsPage = client.get("/api/paymentReceipts") {
            header("X-Caller", "curator:admin")
        }.json()["items"]!!.jsonArray
        assertEquals(2, receiptsPage.size)
        for (item in receiptsPage) {
            val id = item.jsonObject["id"]!!.jsonPrimitive.content
            assertTrue(id.startsWith("rcpt_") && id.length <= 64, "Unexpected receipt id: $id")
        }
    }
}
