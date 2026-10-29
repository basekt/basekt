package io.github.basekt.payment.lemonsqueezy

import io.github.basekt.payment.CreatePaymentRequest
import io.github.basekt.payment.PaymentAmount
import io.github.basekt.payment.PaymentProvider
import io.github.basekt.payment.PaymentStatus
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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LemonSqueezyPaymentProviderTest : PaymentProviderContract() {

    override val contractCurrency: String = "USD"

    private val apiKey = "lsq_api_test_key"
    private val storeId = "10402"
    private val defaultVariantId = "55001"
    private val webhookSecret = "lsq_whsec_test"

    private lateinit var fakeApi: FakeLemonSqueezyApi

    @BeforeTest
    fun resetApi() {
        fakeApi = FakeLemonSqueezyApi()
    }

    override fun createPaymentProvider(): PaymentProvider = LemonSqueezyPaymentProvider(
        settings = LemonSqueezySettings(
            apiKey = apiKey,
            storeId = storeId,
            webhookSecret = webhookSecret,
            defaultVariantId = defaultVariantId,
        ),
        httpClient = fakeApi.httpClient,
    )

    @Test
    fun `sends JSON_API checkout payload with custom_price, reference_id and resolves customer portal URL`() = runTest {
        val provider = createPaymentProvider() as LemonSqueezyPaymentProvider
        val created = provider.createSession(
            CreatePaymentRequest(
                referenceId = "pay_lsq_1",
                amount = PaymentAmount.ofDecimal("29.00", "USD"),
                description = "Quarterly Field Notes",
                successUrl = "https://notes.example/thanks",
                metadata = mapOf("email" to "reader@example.com", "uid" to "acct_7"),
            ),
        )
        assertTrue(created.checkoutUrl!!.startsWith("https://checkout.lemonsqueezy.com/"))

        val lastReq = Json.parseToJsonElement(fakeApi.lastCheckoutBody!!).jsonObject["data"]!!.jsonObject
        val attrs = lastReq["attributes"]!!.jsonObject
        assertEquals(2900, attrs["custom_price"]!!.jsonPrimitive.int)
        val checkoutData = attrs["checkout_data"]!!.jsonObject
        assertEquals("reader@example.com", checkoutData["email"]!!.jsonPrimitive.content)
        assertEquals("pay_lsq_1", checkoutData["custom"]!!.jsonObject["reference_id"]!!.jsonPrimitive.content)
        assertEquals("acct_7", checkoutData["custom"]!!.jsonObject["uid"]!!.jsonPrimitive.content)

        val rels = lastReq["relationships"]!!.jsonObject
        assertEquals(storeId, rels["store"]!!.jsonObject["data"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(defaultVariantId, rels["variant"]!!.jsonObject["data"]!!.jsonObject["id"]!!.jsonPrimitive.content)

        assertEquals("https://store.lemonsqueezy.com/billing?sub=sub_99", provider.customerPortalUrl("sub_99"))
        assertNull(provider.customerPortalUrl("missing_sub"))
    }

    @Test
    fun `verifies X-Signature HMAC, isolates store_id and test_mode, and maps order and subscription events`() = runTest {
        val provider = createPaymentProvider() as LemonSqueezyPaymentProvider

        fun sign(body: String): Map<String, String> {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(webhookSecret.encodeToByteArray(), "HmacSHA256"))
            val hex = mac.doFinal(body.encodeToByteArray()).joinToString("") { "%02x".format(it) }
            return mapOf("X-Signature" to hex)
        }

        // 1. Live order_created settles session
        val orderPaid = """
            {
              "meta": {
                "event_name": "order_created",
                "custom_data": { "reference_id": "pay_lsq_1", "uid": "acct_7" }
              },
              "data": {
                "id": "ord_501",
                "type": "orders",
                "attributes": {
                  "store_id": $storeId,
                  "status": "paid",
                  "currency": "USD",
                  "total": 2900,
                  "test_mode": false,
                  "created_at": "2026-10-30T00:00:00Z",
                  "updated_at": "2026-10-30T00:00:05Z"
                }
              }
            }
        """.trimIndent()
        val verifiedOrder = provider.verifyWebhook(sign(orderPaid), orderPaid)
        assertEquals(PaymentStatus.SUCCEEDED, verifiedOrder.status)
        assertEquals("pay_lsq_1", verifiedOrder.referenceId)
        assertEquals("ord_501", verifiedOrder.paymentReference)
        assertEquals(PaymentAmount.ofDecimal("29.00", "USD"), verifiedOrder.amount)
        assertEquals("order_created:ord_501:2026-10-30T00:00:05Z", verifiedOrder.eventKey)

        // 2. Different store_id is ignored (status = null)
        val foreignStore = orderPaid.replace("\"store_id\": $storeId", "\"store_id\": 99999")
        assertNull(provider.verifyWebhook(sign(foreignStore), foreignStore).status)

        // 3. test_mode=true event on a live provider is ignored (status = null)
        val testEventOnLive = orderPaid.replace("\"test_mode\": false", "\"test_mode\": true")
        assertNull(provider.verifyWebhook(sign(testEventOnLive), testEventOnLive).status)

        // 4. Subscription lifecycle & refund events
        val subActive = """
            {
              "meta": { "event_name": "subscription_created", "custom_data": { "reference_id": "pay_sub_9" } },
              "data": {
                "id": "sub_99",
                "type": "subscriptions",
                "attributes": {
                  "store_id": $storeId,
                  "variant_id": 55001,
                  "status": "active",
                  "renews_at": "2026-11-30T00:00:00Z",
                  "urls": { "customer_portal": "https://store.lemonsqueezy.com/billing?sub=sub_99" },
                  "test_mode": false,
                  "updated_at": "2026-10-30T01:00:00Z"
                }
              }
            }
        """.trimIndent()
        val verifiedSub = provider.verifyWebhook(sign(subActive), subActive)
        assertEquals(PaymentStatus.SUCCEEDED, verifiedSub.status)
        assertEquals("2026-11-30T00:00:00Z", verifiedSub.metadata["renewsAt"])
        assertEquals("https://store.lemonsqueezy.com/billing?sub=sub_99", verifiedSub.metadata["customerPortalUrl"])

        val refundedInvoice = """
            {
              "meta": { "event_name": "subscription_payment_refunded", "custom_data": { "reference_id": "pay_sub_9" } },
              "data": {
                "id": "inv_77",
                "type": "subscription-invoices",
                "attributes": {
                  "store_id": $storeId,
                  "subscription_id": "sub_99",
                  "status": "refunded",
                  "refunded": true,
                  "test_mode": false,
                  "updated_at": "2026-10-30T02:00:00Z"
                }
              }
            }
        """.trimIndent()
        val verifiedRefund = provider.verifyWebhook(sign(refundedInvoice), refundedInvoice)
        assertEquals(PaymentStatus.REFUNDED, verifiedRefund.status)
        assertEquals("sub_99", verifiedRefund.paymentReference)
    }
}

private class FakeLemonSqueezyApi {
    private val seq = AtomicLong(0)
    private val checkouts = ConcurrentHashMap<String, String>()

    @Volatile
    var lastCheckoutBody: String? = null

    val httpClient = HttpClient(
        MockEngine { request ->
            val path = request.url.encodedPath
            val jsonApiHeaders = headersOf(HttpHeaders.ContentType, "application/vnd.api+json")
            when {
                request.method == HttpMethod.Post && path.endsWith("/checkouts") -> {
                    require(request.headers[HttpHeaders.Accept] == "application/vnd.api+json")
                    val raw = request.body.toByteArray().decodeToString()
                    lastCheckoutBody = raw
                    val id = "chk_${seq.incrementAndGet()}"
                    val url = "https://checkout.lemonsqueezy.com/checkout/buy/$id"
                    checkouts[id] = url
                    respond(
                        content = """{"data":{"id":"$id","type":"checkouts","attributes":{"url":"$url"}}}""",
                        status = HttpStatusCode.Created,
                        headers = jsonApiHeaders,
                    )
                }
                request.method == HttpMethod.Get && path.contains("/checkouts/") -> {
                    val id = path.substringAfterLast('/')
                    val url = checkouts[id]
                    if (url == null) {
                        respond("""{"errors":[{"status":"404","title":"Not Found"}]}""", HttpStatusCode.NotFound, jsonApiHeaders)
                    } else {
                        respond(
                            content = """{"data":{"id":"$id","type":"checkouts","attributes":{"url":"$url"}}}""",
                            status = HttpStatusCode.OK,
                            headers = jsonApiHeaders,
                        )
                    }
                }
                request.method == HttpMethod.Get && path.endsWith("/subscriptions/sub_99") -> respond(
                    content = """{"data":{"id":"sub_99","type":"subscriptions","attributes":{"urls":{"customer_portal":"https://store.lemonsqueezy.com/billing?sub=sub_99"}}}}""",
                    status = HttpStatusCode.OK,
                    headers = jsonApiHeaders,
                )
                request.method == HttpMethod.Get && path.contains("/subscriptions/") -> respond(
                    content = """{"errors":[{"status":"404","title":"Not Found"}]}""",
                    status = HttpStatusCode.NotFound,
                    headers = jsonApiHeaders,
                )
                else -> error("Unexpected Lemon Squeezy path: ${request.method.value} $path")
            }
        },
    )
}
