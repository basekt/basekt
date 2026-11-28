package io.github.basekt.payments

import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.payment.hitpay.HitPayPaymentProvider
import io.github.basekt.payment.hitpay.HitPaySettings
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
import io.github.basekt.schema.schema
import io.github.basekt.server.BaseKt
import io.github.basekt.server.Identity
import io.github.basekt.server.IdentityResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A pottery studio taking `bookings` for classes, paid in `PHP` through HitPay. */
private val potterySchema = schema {
    paymentLedger(readers = setOf("admin"))
    resource("bookings") {
        string("className") { required() }
        decimal("fee") { required() }
        string("email")
        enum("state", listOf("held", "paid", "failed", "refunded")) { required() }
        permissions { read(everyone) }
        payable {
            amount("fee")
            fixedCurrency("PHP")
            description("className")
            buyerEmail("email")
            statusField("state", paid = "paid", failed = "failed", refunded = "refunded")
        }
    }
}

class HitPayPaymentsTest {
    private val salt = "hitpay_pottery_salt"
    private val requestId = "9e9be41b-2866-4307-8621-e35c633c431f"
    private val paymentId = "9e9be41c-2869-4b8c-8701-6c44af86b7d0"

    private val callers = IdentityResolver { call ->
        call.request.headers["X-Caller"]?.let { raw ->
            Identity(raw.substringBefore(':'), raw.substringAfter(':', "").split(',').filter { it.isNotEmpty() }.toSet())
        }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun signature(body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt.encodeToByteArray(), "HmacSHA256"))
        return mac.doFinal(body.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `a booking is paid through a HitPay webhook, a redelivery is skipped, and an admin refunds it`() = testApplication {
        val source = InMemoryDataSource()
        val bookingId = runBlocking {
            val created = source.create(
                potterySchema.resource("bookings")!!,
                mapOf(
                    "className" to Value.Text("Wheel-throwing, Saturday"),
                    "fee" to Value.Decimal.of("1250.00"),
                    "email" to Value.Text("potter@example.com"),
                    "state" to Value.Text("held"),
                ),
            )
            (created[Resource.ID] as Value.Text).value
        }

        var created: JsonObject? = null
        var refunded: JsonObject? = null
        val hitpay = HitPayPaymentProvider(
            HitPaySettings(apiKey = "hitpay_test_key", webhookSalt = salt, sandbox = true),
            HttpClient(
                MockEngine { request ->
                    val json = headersOf(HttpHeaders.ContentType, "application/json")
                    val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString().ifEmpty { "{}" }).jsonObject
                    when {
                        request.method == HttpMethod.Post && request.url.encodedPath == "/v1/payment-requests" -> {
                            created = body
                            respond(
                                """{"id":"$requestId","amount":"1250.00","currency":"PHP","status":"pending","reference_number":"${body["reference_number"]!!.jsonPrimitive.content}","url":"https://securecheckout.sandbox.hit-pay.com/payment-request/@pottery/$requestId/checkout","payments":[]}""",
                                HttpStatusCode.OK,
                                json,
                            )
                        }
                        request.method == HttpMethod.Post && request.url.encodedPath == "/v1/refund" -> {
                            refunded = body
                            respond(
                                """{"id":"9ef6957e-6b1b-4e08-8cde-d5f5c908418d","payment_id":"$paymentId","amount_refunded":1250.00,"currency":"php","status":"succeeded"}""",
                                HttpStatusCode.OK,
                                json,
                            )
                        }
                        else -> error("Unexpected HitPay call: ${request.method.value} ${request.url.encodedPath}")
                    }
                },
            ),
        )

        val settlements = mutableListOf<PaymentSettlement>()
        application {
            install(BaseKt) {
                dataSource = source
                schema = potterySchema
                identities = callers
            }
            install(BaseKtPayments) {
                provider(hitpay)
                onSettled { settlements += it }
            }
        }

        val providers = Json.parseToJsonElement(client.get("/payments/providers").bodyAsText()).jsonArray
        val listed = providers.single().jsonObject
        assertEquals("hitpay", listed["id"]!!.jsonPrimitive.content)
        assertTrue(listed["webhook"]!!.jsonPrimitive.boolean)
        assertTrue(listed["refundable"]!!.jsonPrimitive.boolean)

        // The amount comes from the booking on the server, and the buyer's email goes to HitPay.
        val opened = client.post("/payments/bookings/$bookingId") {
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"hitpay"}""")
        }
        assertEquals(HttpStatusCode.Created, opened.status)
        val sessionId = opened.json()["id"]!!.jsonPrimitive.content
        assertEquals("https://securecheckout.sandbox.hit-pay.com/payment-request/@pottery/$requestId/checkout", opened.json()["checkoutUrl"]!!.jsonPrimitive.content)
        assertEquals("1250.00", created!!["amount"]!!.jsonPrimitive.content)
        assertEquals(sessionId, created!!["reference_number"]!!.jsonPrimitive.content)
        assertEquals("potter@example.com", created!!["email"]!!.jsonPrimitive.content)
        assertEquals(bookingId, created!!["metadata"]!!.jsonObject["recordId"]!!.jsonPrimitive.content)

        val completed = """
            {"id":"$requestId","amount":"1250.00","currency":"PHP","status":"completed","reference_number":"$sessionId",
             "updated_at":"2026-11-28T15:17:43",
             "payments":[{"id":"$paymentId","status":"succeeded","amount":"1250.00","currency":"php","payment_type":"gcash"}]}
        """.trimIndent()

        val forged = client.post("/payments/webhooks/hitpay") {
            header("Hitpay-Signature", "0".repeat(64))
            setBody(completed)
        }
        assertEquals(HttpStatusCode.BadRequest, forged.status)
        assertEquals("held", client.get("/api/bookings/$bookingId").json()["state"]!!.jsonPrimitive.content)

        val delivered = client.post("/payments/webhooks/hitpay") {
            header("Hitpay-Signature", signature(completed))
            header("Hitpay-Event-Object", "payment_request")
            header("Hitpay-Event-Type", "completed")
            contentType(ContentType.Application.Json)
            setBody(completed)
        }
        assertEquals(HttpStatusCode.OK, delivered.status)
        assertFalse(delivered.json()["duplicate"]!!.jsonPrimitive.boolean)
        assertEquals("succeeded", delivered.json()["status"]!!.jsonPrimitive.content)
        assertEquals("paid", client.get("/api/bookings/$bookingId").json()["state"]!!.jsonPrimitive.content)
        assertEquals(1, settlements.size)

        val redelivered = client.post("/payments/webhooks/hitpay") {
            header("Hitpay-Signature", signature(completed))
            setBody(completed)
        }
        assertTrue(redelivered.json()["duplicate"]!!.jsonPrimitive.boolean)
        assertEquals(1, settlements.size)

        // The refund goes to the payment the webhook named.
        assertEquals(
            HttpStatusCode.Forbidden,
            client.post("/payments/sessions/$sessionId/refund") { header("X-Caller", "student:member") }.status,
        )
        val refund = client.post("/payments/sessions/$sessionId/refund") {
            header("X-Caller", "owner:admin")
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"class cancelled"}""")
        }
        assertEquals(HttpStatusCode.OK, refund.status)
        assertEquals("refunded", refund.json()["status"]!!.jsonPrimitive.content)
        assertEquals(paymentId, refunded!!["payment_id"]!!.jsonPrimitive.content)
        assertEquals("1250.00", refunded!!["amount"]!!.jsonPrimitive.content)
        assertEquals("refunded", client.get("/api/bookings/$bookingId").json()["state"]!!.jsonPrimitive.content)
    }
}
