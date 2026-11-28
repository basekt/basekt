package io.github.basekt.payment.hitpay

import io.github.basekt.payment.CreatePaymentRequest
import io.github.basekt.payment.PaymentAmount
import io.github.basekt.payment.PaymentException
import io.github.basekt.payment.PaymentProvider
import io.github.basekt.payment.PaymentStatus
import io.github.basekt.payment.RefundRequest
import io.github.basekt.payment.WebhookPayload
import io.github.basekt.payment.WebhookSignatureException
import io.github.basekt.testing.PaymentProviderContract
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class HitPayPaymentProviderTest : PaymentProviderContract() {

    private val apiKey = "hitpay_test_business_key"
    private val salt = "hitpay_webhook_salt"
    private val now = Instant.parse("2026-11-28T08:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = this@HitPayPaymentProviderTest.now
    }

    private lateinit var api: FakeHitPayApi

    @BeforeTest
    fun resetApi() {
        api = FakeHitPayApi(apiKey)
    }

    override fun createPaymentProvider(): PaymentProvider = provider()

    private fun provider(
        paymentMethods: List<String> = emptyList(),
        webhookSalt: String? = salt,
    ) = HitPayPaymentProvider(
        settings = HitPaySettings(
            apiKey = apiKey,
            webhookSalt = webhookSalt,
            sandbox = true,
            paymentMethods = paymentMethods,
            defaultSuccessUrl = "https://studio.example/paid",
        ),
        httpClient = api.httpClient,
        clock = clock,
    )

    private fun signed(body: String, vararg headers: Pair<String, String>, key: String = salt): WebhookPayload {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.encodeToByteArray(), "HmacSHA256"))
        val hex = mac.doFinal(body.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        return WebhookPayload(rawBody = body, headers = mapOf("Hitpay-Signature" to hex) + headers)
    }

    @Test
    fun `sends the payment request with the amount, reference, buyer and methods HitPay reads`() = runTest {
        val session = provider(paymentMethods = listOf("gcash", "qrph_netbank")).createSession(
            CreatePaymentRequest(
                referenceId = "pay_pottery_1",
                amount = PaymentAmount.ofDecimal("450", "PHP"),
                description = "Wheel-throwing workshop, Saturday",
                expiresAt = now + 30.minutes,
                metadata = mapOf("email" to "potter@example.com", "resource" to "registrations", "recordId" to "reg_7"),
            ),
        )

        val sent = api.lastCreate!!
        assertEquals(apiKey, sent.apiKey)
        assertEquals("XMLHttpRequest", sent.requestedWith)
        assertTrue(sent.url.startsWith("https://api.sandbox.hit-pay.com/v1/payment-requests"))
        val body = sent.body
        assertEquals("450.00", body["amount"]!!.jsonPrimitive.content)
        assertFalse(body["amount"]!!.jsonPrimitive.isString, "amount is a JSON number, as HitPay's spec says")
        assertEquals("PHP", body["currency"]!!.jsonPrimitive.content)
        assertEquals("pay_pottery_1", body["reference_number"]!!.jsonPrimitive.content)
        assertEquals("Wheel-throwing workshop, Saturday", body["purpose"]!!.jsonPrimitive.content)
        assertEquals("https://studio.example/paid", body["redirect_url"]!!.jsonPrimitive.content)
        assertEquals("potter@example.com", body["email"]!!.jsonPrimitive.content)
        assertEquals(listOf("gcash", "qrph_netbank"), body["payment_methods"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("30 mins", body["expires_after"]!!.jsonPrimitive.content)
        assertEquals("false", body["send_email"]!!.jsonPrimitive.content)
        assertEquals(
            mapOf("resource" to "registrations", "recordId" to "reg_7"),
            body["metadata"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
        assertNull(body["webhook"], "the deprecated per-request webhook is not sent")

        assertEquals(PaymentStatus.PENDING, session.status)
        assertTrue(session.checkoutUrl!!.startsWith("https://securecheckout.sandbox.hit-pay.com/"))
        assertEquals("pay_pottery_1", session.metadata["referenceId"])
    }

    @Test
    fun `an expiry already past and HitPay's validation errors are refused with HitPay's message`() = runTest {
        val provider = provider()
        assertFailsWith<PaymentException> {
            provider.createSession(
                CreatePaymentRequest("pay_late", PaymentAmount.ofDecimal("100", "PHP"), expiresAt = now - 1.minutes),
            )
        }
        val refused = assertFailsWith<PaymentException> {
            provider.createSession(CreatePaymentRequest("pay_tiny", PaymentAmount.ofDecimal("0.10", "PHP")))
        }
        assertContains(refused.message!!, "HTTP 422")
        assertContains(refused.message!!, "must be between 0.3 and 999999999.99")
    }

    @Test
    fun `reads a paid request back with its payment, and a refunded one as refunded`() = runTest {
        val provider = provider()
        val session = provider.createSession(CreatePaymentRequest("pay_read_1", PaymentAmount.ofDecimal("125.50", "PHP")))
        val paymentId = api.pay(session.externalId, paymentType = "gcash")

        val paid = provider.getSession(session.externalId)!!
        assertEquals(PaymentStatus.SUCCEEDED, paid.status)
        assertEquals(paymentId, paid.paymentReference)
        assertEquals(PaymentAmount.ofDecimal("125.50", "PHP"), paid.amount)
        assertEquals("pay_read_1", paid.metadata["referenceId"])
        assertEquals("gcash", paid.metadata["paymentType"])

        api.markRefunded(session.externalId)
        assertEquals(PaymentStatus.REFUNDED, provider.getSession(session.externalId)!!.status)

        val calls = api.calls.size
        assertNull(provider.getSession("../payment-requests"), "a non-HitPay id is never sent into a path")
        assertEquals(calls, api.calls.size)
    }

    @Test
    fun `refunds the paid payment, finding its id from the request when the ledger has none`() = runTest {
        val provider = provider()
        val session = provider.createSession(CreatePaymentRequest("pay_refund_1", PaymentAmount.ofDecimal("450", "PHP")))
        assertFailsWith<PaymentException>("nothing paid yet") {
            provider.refund(RefundRequest(session.externalId, amount = PaymentAmount.ofDecimal("450", "PHP")))
        }
        val paymentId = api.pay(session.externalId)

        val receipt = provider.refund(RefundRequest(session.externalId, amount = PaymentAmount.ofDecimal("450", "PHP")))
        assertEquals(paymentId, api.lastRefund!!["payment_id"]!!.jsonPrimitive.content)
        assertEquals("450.00", api.lastRefund!!["amount"]!!.jsonPrimitive.content)
        assertEquals("succeeded", receipt.status)
        assertEquals(PaymentAmount.ofDecimal("450", "PHP"), receipt.amount)

        val partial = provider.refund(
            RefundRequest(session.externalId, paymentReference = paymentId, amount = PaymentAmount.ofDecimal("50.25", "PHP")),
        )
        assertEquals(PaymentAmount.ofDecimal("50.25", "PHP"), partial.amount)
    }

    @Test
    fun `verifies Hitpay-Signature over the raw body and settles a completed payment request`() = runTest {
        val provider = provider()
        val requestId = "9e9be41b-2866-4307-8621-e35c633c431f"
        val paymentId = "9e9be41c-2869-4b8c-8701-6c44af86b7d0"
        val completed = """
            {
              "id": "$requestId",
              "email": "potter@example.com",
              "amount": "450.00",
              "currency": "PHP",
              "status": "completed",
              "purpose": "Wheel-throwing workshop, Saturday",
              "reference_number": "pay_pottery_1",
              "payment_methods": ["gcash", "qrph_netbank"],
              "created_at": "2026-11-28T15:17:21",
              "updated_at": "2026-11-28T15:17:43",
              "payments": [
                {
                  "id": "$paymentId",
                  "status": "succeeded",
                  "buyer_email": "potter@example.com",
                  "currency": "php",
                  "amount": "450.00",
                  "refunded_amount": "0.00",
                  "payment_type": "gcash",
                  "fees": "10.35",
                  "created_at": "2026-11-28T15:17:21",
                  "updated_at": "2026-11-28T15:17:43"
                }
              ]
            }
        """.trimIndent()

        val event = provider.verifyWebhook(
            signed(completed, "Hitpay-Event-Object" to "payment_request", "Hitpay-Event-Type" to "completed"),
        )
        assertEquals(PaymentStatus.SUCCEEDED, event.status)
        assertEquals("pay_pottery_1", event.referenceId)
        assertEquals(requestId, event.externalSessionId)
        assertEquals(paymentId, event.paymentReference)
        assertEquals(PaymentAmount.ofDecimal("450", "PHP"), event.amount)
        assertEquals("payment_request:$requestId:completed:2026-11-28T15:17:43", event.eventKey)
        assertEquals("gcash", event.metadata["paymentType"])
        assertNull(event.occurredAt, "a timestamp without an offset is not guessed")

        // The same event without HitPay's object header is still read as a payment request.
        assertEquals(event.eventKey, provider.verifyWebhook(signed(completed)).eventKey)

        val failed = completed.replace("\"status\": \"completed\"", "\"status\": \"failed\"")
            .replace("\"status\": \"succeeded\"", "\"status\": \"failed\"")
        assertEquals(PaymentStatus.FAILED, provider.verifyWebhook(signed(failed)).status)
    }

    @Test
    fun `refuses tampered bodies, other salts and a provider without a salt`() = runTest {
        val body = """{"id":"9e9be41b-2866-4307-8621-e35c633c431f","status":"completed","reference_number":"pay_1","payments":[]}"""
        val good = signed(body)
        assertFailsWith<WebhookSignatureException> {
            provider().verifyWebhook(good.copy(rawBody = body.replace("pay_1", "pay_2")))
        }
        assertFailsWith<WebhookSignatureException> { provider().verifyWebhook(signed(body, key = "another_salt")) }
        assertFailsWith<WebhookSignatureException> { provider(webhookSalt = null).verifyWebhook(good) }
        assertFailsWith<WebhookSignatureException> { provider().verifyWebhook(signed("[1,2]")) }
        assertEquals(
            PaymentStatus.SUCCEEDED,
            provider().verifyWebhook(good.copy(headers = mapOf("hitpay-signature" to good.headers.values.single().uppercase()))).status,
            "header names and hex case don't matter",
        )
    }

    @Test
    fun `charge events settle by their payment request, and other objects are informational`() = runTest {
        val provider = provider()
        val charge = """
            {
              "id": "9a934d94-a41f-46c7-a934-c1ebf2436008",
              "payment_request_id": "9e9be41b-2866-4307-8621-e35c633c431f",
              "status": "refunded",
              "amount": 450.00,
              "currency": "php",
              "refunded_amount": 450.00,
              "payment_type": "gcash",
              "updated_at": "2026-11-29T09:17:52.000000Z"
            }
        """.trimIndent()
        val refunded = provider.verifyWebhook(signed(charge, "Hitpay-Event-Object" to "charge", "Hitpay-Event-Type" to "updated"))
        assertEquals(PaymentStatus.REFUNDED, refunded.status)
        assertEquals("9e9be41b-2866-4307-8621-e35c633c431f", refunded.externalSessionId)
        assertEquals("9a934d94-a41f-46c7-a934-c1ebf2436008", refunded.paymentReference)
        assertEquals(PaymentAmount.ofDecimal("450", "PHP"), refunded.amount)
        assertEquals(Instant.parse("2026-11-29T09:17:52Z"), refunded.occurredAt)

        val payout = """{"id":"9b000000-0000-4000-8000-000000000001","status":"succeeded","bank_name":"BDO"}"""
        val ignored = provider.verifyWebhook(signed(payout, "Hitpay-Event-Object" to "payout", "Hitpay-Event-Type" to "created"))
        assertNull(ignored.status)
        assertEquals("unsupported_object", ignored.metadata["ignoredReason"])
    }

    @Test
    fun `settings pick the sandbox, refuse method names that aren't codes, and keep secrets out of toString`() {
        assertEquals(HitPaySettings.LIVE_API_BASE_URL, HitPaySettings(apiKey = "k").apiBaseUrl)
        assertEquals(HitPaySettings.SANDBOX_API_BASE_URL, HitPaySettings(apiKey = "k", sandbox = true).apiBaseUrl)
        val refused = assertFailsWith<IllegalArgumentException> {
            HitPaySettings(apiKey = "k", paymentMethods = listOf("gcash", "GCash", "qr ph"))
        }
        assertContains(refused.message!!, "'GCash', 'qr ph'")
        assertFailsWith<IllegalArgumentException> { HitPaySettings(apiKey = " ") }
        assertFailsWith<IllegalArgumentException> { HitPaySettings(apiKey = "k", supportedCurrencies = emptySet()) }

        val shown = HitPaySettings(apiKey = "secret_key_123", webhookSalt = "secret_salt_456").toString()
        assertFalse("secret_key_123" in shown || "secret_salt_456" in shown, shown)
    }
}

/** A HitPay API in memory: payment requests, their payments and refunds, refusing calls without the API key. */
private class FakeHitPayApi(private val apiKey: String) {
    class Sent(val url: String, val apiKey: String?, val requestedWith: String?, val body: JsonObject)

    private val requests = ConcurrentHashMap<String, JsonObject>()
    val calls = CopyOnWriteArrayList<String>()

    @Volatile
    var lastCreate: Sent? = null

    @Volatile
    var lastRefund: JsonObject? = null

    fun pay(requestId: String, paymentType: String = "qrph_netbank"): String {
        val paymentId = UUID.randomUUID().toString()
        requests.computeIfPresent(requestId) { _, request ->
            val amount = request["amount"]!!.jsonPrimitive.content
            val payment = """{"id":"$paymentId","status":"succeeded","amount":"$amount","currency":"php","payment_type":"$paymentType","refunded_amount":"0.00"}"""
            Json.parseToJsonElement(
                request.toString().replace("\"status\":\"pending\"", "\"status\":\"completed\"").replace("\"payments\":[]", "\"payments\":[$payment]"),
            ).jsonObject
        }
        return paymentId
    }

    fun markRefunded(requestId: String) {
        requests.computeIfPresent(requestId) { _, request ->
            Json.parseToJsonElement(request.toString().replace("\"status\":\"succeeded\"", "\"status\":\"refunded\"")).jsonObject
        }
    }

    val httpClient = HttpClient(
        MockEngine { request ->
            val path = request.url.encodedPath
            calls += "${request.method.value} $path"
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            if (request.headers["X-BUSINESS-API-KEY"] != apiKey) {
                return@MockEngine respond("""{"message":"Invalid business api key"}""", HttpStatusCode.Unauthorized, json)
            }
            when {
                request.method == HttpMethod.Post && path == "/v1/payment-requests" -> {
                    val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                    lastCreate = Sent(request.url.toString(), request.headers["X-BUSINESS-API-KEY"], request.headers["X-Requested-With"], body)
                    val amount = body["amount"]!!.jsonPrimitive.content.toBigDecimal()
                    if (amount < "0.3".toBigDecimal()) {
                        return@MockEngine respond(
                            """{"message":"The given data was invalid.","errors":{"amount":["The amount must be between 0.3 and 999999999.99."]}}""",
                            HttpStatusCode.UnprocessableEntity,
                            json,
                        )
                    }
                    val id = UUID.randomUUID().toString()
                    val created = Json.parseToJsonElement(
                        """
                        {"id":"$id","amount":"${amount.setScale(2)}","currency":"${body["currency"]!!.jsonPrimitive.content}",
                         "status":"pending","reference_number":"${body["reference_number"]!!.jsonPrimitive.content}",
                         "url":"https://securecheckout.sandbox.hit-pay.com/payment-request/@studio/$id/checkout","payments":[]}
                        """.trimIndent(),
                    ).jsonObject
                    requests[id] = created
                    respond(created.toString(), HttpStatusCode.OK, json)
                }
                request.method == HttpMethod.Get && path.startsWith("/v1/payment-requests/") -> {
                    val found = requests[path.substringAfterLast('/')]
                        ?: return@MockEngine respond(
                            """{"message":"No query results for model [App\\Business\\PaymentRequest]"}""",
                            HttpStatusCode.NotFound,
                            json,
                        )
                    respond(found.toString(), HttpStatusCode.OK, json)
                }
                request.method == HttpMethod.Post && path == "/v1/refund" -> {
                    val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                    lastRefund = body
                    respond(
                        """{"id":"${UUID.randomUUID()}","payment_id":"${body["payment_id"]!!.jsonPrimitive.content}","amount_refunded":${body["amount"]},"currency":"php","status":"succeeded"}""",
                        HttpStatusCode.OK,
                        json,
                    )
                }
                else -> error("Unexpected HitPay call: ${request.method.value} $path")
            }
        },
    )
}
