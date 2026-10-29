package io.github.basekt.payment.paymongo

import io.github.basekt.payment.PaymentAmount
import io.github.basekt.payment.PaymentProvider
import io.github.basekt.payment.PaymentStatus
import io.github.basekt.payment.RefundRequest
import io.github.basekt.payment.WebhookSignatureException
import io.github.basekt.testing.PaymentProviderContract
import io.github.ronjunevaldoz.paymongo.PayMongo
import io.github.ronjunevaldoz.paymongo.serialization.PayMongoJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.http.path
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Instant

class PayMongoPaymentProviderTest : PaymentProviderContract() {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(1_760_000_000L)
    }

    override fun createPaymentProvider(): PaymentProvider = mockProvider(PayMongoFlow.CHECKOUT_SESSION_V2)

    private fun mockProvider(flow: PayMongoFlow): PayMongoPaymentProvider {
        val counter = AtomicInteger(0)
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            when {
                request.method == HttpMethod.Post && path == "/v2/checkout_sessions" -> {
                    val id = "cs_v2_${counter.incrementAndGet()}"
                    respond(
                        content = """{"data":{"id":"$id","attributes":{"checkout_url":"https://checkout.paymongo.com/$id","livemode":false,"created_at":1760000000,"updated_at":1760000000}}}""",
                        status = HttpStatusCode.OK,
                        headers = jsonHeaders,
                    )
                }
                request.method == HttpMethod.Post && path == "/v1/payment_links" -> {
                    val id = "link_${counter.incrementAndGet()}"
                    respond(
                        content = """{"data":{"id":"$id","amount":12500,"currency":"PHP","description":"Order","status":"unpaid","livemode":false,"url":"https://pm.link/$id","reference_number":"REF$id","created_at":"2026-10-30T00:00:00Z","updated_at":"2026-10-30T00:00:00Z"}}""",
                        status = HttpStatusCode.OK,
                        headers = jsonHeaders,
                    )
                }
                request.method == HttpMethod.Get && path.startsWith("/v1/payment_links/link_") -> {
                    val id = path.substringAfterLast('/')
                    respond(
                        content = """{"data":{"id":"$id","amount":12500,"currency":"PHP","description":"Order","status":"unpaid","livemode":false,"url":"https://pm.link/$id","reference_number":"REF$id","created_at":"2026-10-30T00:00:00Z","updated_at":"2026-10-30T00:00:00Z"}}""",
                        status = HttpStatusCode.OK,
                        headers = jsonHeaders,
                    )
                }
                request.method == HttpMethod.Post && path.endsWith("/refunds") -> {
                    respond(
                        content = """{"data":{"id":"rfnd_1","amount":12500,"currency":"PHP","status":"succeeded","payment_id":"pay_99","reason":"requested_by_customer","livemode":false,"created_at":"2026-10-30T00:00:00Z","updated_at":"2026-10-30T00:00:00Z"}}""",
                        status = HttpStatusCode.OK,
                        headers = jsonHeaders,
                    )
                }
                else -> respond(
                    content = """{"errors":[{"code":"resource_not_found","detail":"Not found"}]}""",
                    status = HttpStatusCode.NotFound,
                    headers = jsonHeaders,
                )
            }
        }
        val httpClient = HttpClient(engine) {
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
        val settings = PayMongoSettings(
            secretKey = "sk_test_123",
            webhookSecretKey = "whsec_test_secret",
            flow = flow,
            clock = fixedClock,
        )
        return PayMongoPaymentProvider(settings, PayMongo(PayMongo.Config(secretKey = "sk_test_123"), httpClient))
    }

    @Test
    fun `rejects maya and atome on checkout sessions with guidance`() {
        val error = assertFailsWith<IllegalArgumentException> {
            PayMongoSettings(secretKey = "sk_test_123", paymentMethodTypes = listOf("gcash", "maya"))
        }
        assertEquals(true, error.message?.contains("paymaya"))
    }

    @Test
    fun `verifies valid Paymongo-Signature and enforces timestamp replay window`() = runTest {
        val provider = mockProvider(PayMongoFlow.CHECKOUT_SESSION_V2)
        val rawBody = """
            {
              "data": {
                "id": "evt_live_1",
                "type": "event",
                "attributes": {
                  "type": "checkout_session.payment.paid",
                  "livemode": false,
                  "created_at": 1760000000,
                  "updated_at": 1760000000,
                  "data": {
                    "id": "cs_v2_1",
                    "type": "checkout_session",
                    "attributes": {
                      "reference_number": "pay-order-77",
                      "metadata": { "basekt_reference_id": "pay-order-77" },
                      "payments": [{ "id": "pay_settled_77" }],
                      "line_items": [{ "amount": 25000, "quantity": 1 }]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val freshTs = 1_760_000_010L
        val freshSig = sign("whsec_test_secret", "$freshTs.$rawBody")
        val verified = provider.verifyWebhook(
            headers = mapOf("paymongo-signature" to "t=$freshTs,te=$freshSig,li="),
            rawBody = rawBody,
        )
        assertEquals("evt_live_1", verified.eventKey)
        assertEquals(PaymentStatus.SUCCEEDED, verified.status)
        assertEquals("cs_v2_1", verified.externalSessionId)
        assertEquals("pay-order-77", verified.referenceId)
        assertEquals("pay_settled_77", verified.paymentReference)
        assertEquals(PaymentAmount.ofDecimal("250.00", "PHP"), verified.amount)

        // Same valid HMAC with a stale timestamp outside 300s window is rejected
        val staleTs = 1_759_990_000L
        val staleSig = sign("whsec_test_secret", "$staleTs.$rawBody")
        assertFailsWith<WebhookSignatureException> {
            provider.verifyWebhook(
                headers = mapOf("Paymongo-Signature" to "t=$staleTs,te=$staleSig,li="),
                rawBody = rawBody,
            )
        }
    }

    @Test
    fun `creates payment link and executes link refund`() = runTest {
        val provider = mockProvider(PayMongoFlow.PAYMENT_LINK)
        val linkSession = provider.createSession(
            io.github.basekt.payment.CreatePaymentRequest(
                referenceId = "order-link-1",
                amount = PaymentAmount.ofDecimal("125.00", "PHP"),
                description = "Bakery box",
            ),
        )
        assertEquals("https://pm.link/${linkSession.externalId}", linkSession.checkoutUrl)

        val refund = provider.refund(
            RefundRequest(
                externalSessionId = linkSession.externalId,
                paymentReference = "pay_99",
                amount = PaymentAmount.ofDecimal("125.00", "PHP"),
            ),
        )
        assertEquals("rfnd_1", refund.refundId)
        assertEquals("succeeded", refund.status)
    }

    private fun sign(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.encodeToByteArray(), "HmacSHA256"))
        return mac.doFinal(payload.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
