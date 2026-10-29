package io.github.basekt.payment.paymentwall

import io.github.basekt.payment.CreatePaymentRequest
import io.github.basekt.payment.PaymentAmount
import io.github.basekt.payment.PaymentProvider
import io.github.basekt.payment.PaymentStatus
import io.github.basekt.payment.WebhookForbiddenException
import io.github.basekt.payment.WebhookPayload
import io.github.basekt.payment.WebhookSignatureException
import io.github.basekt.testing.PaymentProviderContract
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaymentwallPaymentProviderTest : PaymentProviderContract() {

    override val contractCurrency: String = "USD"
    override val contractWebhookRemoteIp: String = "174.36.92.186"

    private val projectKey = "pw_proj_key_test"
    private val secretKey = "pw_secret_live_key"
    private val testSecretKey = "pw_secret_test_key"

    override fun createPaymentProvider(): PaymentProvider = PaymentwallPaymentProvider(
        PaymentwallSettings(
            projectKey = projectKey,
            secretKey = secretKey,
            testSecretKey = testSecretKey,
            defaultSuccessUrl = "https://catalog.example/thanks",
        ),
    )

    @Test
    fun `builds Signature v3 widget URLs for both fixed items and recurring subscriptions`() = runTest {
        val provider = PaymentwallPaymentProvider(
            PaymentwallSettings(
                projectKey = projectKey,
                secretKey = secretKey,
                testMode = true,
                subscriptionsByGoodsId = mapOf(
                    "plan_annual" to PaymentwallSubscriptionSpec(1, PaymentwallPeriodType.YEAR),
                ),
            ),
        )

        // 1. One-time fixed checkout (/api/ps)
        val fixed = provider.createSession(
            CreatePaymentRequest(
                referenceId = "pay_fixed_1",
                amount = PaymentAmount.ofDecimal("9", "USD"),
                description = "Digital Print #7",
                successUrl = "https://catalog.example/done?order=1",
                metadata = mapOf("email" to "buyer@example.com"),
            ),
        )
        val fixedUrl = Url(fixed.checkoutUrl!!)
        assertEquals("/api/ps", fixedUrl.encodedPath)
        val fixedParams = fixedUrl.parameters.entries().associate { (k, v) -> k to v.firstOrNull().orEmpty() }
        assertEquals("fixed", fixedParams["ag_type"])
        assertEquals("9.00", fixedParams["amount"])
        assertEquals("USD", fixedParams["currencyCode"])
        assertEquals("1", fixedParams["evaluation"])
        assertEquals("buyer@example.com", fixedParams["email"])
        assertTrue(PaymentwallSignatures.verifyV3(fixedParams, secretKey))

        // 2. Recurring subscription checkout (/api/subscription) with custom uid + goodsId + reference_id passthrough
        val recurring = provider.createSession(
            CreatePaymentRequest(
                referenceId = "pay_sub_1",
                amount = PaymentAmount.ofDecimal("79.00", "USD"),
                description = "Annual Archive Access",
                metadata = mapOf("uid" to "acct_99", "goodsId" to "plan_annual"),
            ),
        )
        val subUrl = Url(recurring.checkoutUrl!!)
        assertEquals("/api/subscription", subUrl.encodedPath)
        val subParams = subUrl.parameters.entries().associate { (k, v) -> k to v.firstOrNull().orEmpty() }
        assertEquals("subscription", subParams["ag_type"])
        assertEquals("1", subParams["ag_period_length"])
        assertEquals("year", subParams["ag_period_type"])
        assertEquals("1", subParams["ag_recurring"])
        assertEquals("acct_99", subParams["uid"])
        assertEquals("plan_annual", subParams["ag_external_id"])
        assertEquals("pay_sub_1", subParams["reference_id"])
        assertTrue(PaymentwallSignatures.verifyV3(subParams, secretKey))
    }

    @Test
    fun `enforces Paymentwall IP allowlist including 216_127_71_0_24 CIDR and IPv4-mapped IPv6`() = runTest {
        assertTrue(PaymentwallIpAllowlist.isAllowed("174.36.92.186"))
        assertTrue(PaymentwallIpAllowlist.isAllowed("216.127.71.0"))
        assertTrue(PaymentwallIpAllowlist.isAllowed("216.127.71.255"))
        assertTrue(PaymentwallIpAllowlist.isAllowed("::ffff:174.37.14.28"))
        assertFalse(PaymentwallIpAllowlist.isAllowed("216.127.72.1"))
        assertFalse(PaymentwallIpAllowlist.isAllowed("203.0.113.50"))

        val provider = createPaymentProvider() as PaymentwallPaymentProvider
        val baseParams = mapOf(
            "uid" to "pay_100",
            "goodsid" to "pay_100",
            "slength" to "",
            "speriod" to "",
            "type" to "0",
            "ref" to "pw_tx_100",
            "sign_version" to "3",
        )
        val signed = baseParams + ("sign" to PaymentwallSignatures.signV3(baseParams, secretKey))

        // Unauthorized IP -> WebhookForbiddenException (maps to 403 Forbidden)
        assertFailsWith<WebhookForbiddenException> {
            provider.verifyWebhook(WebhookPayload(queryParameters = signed, remoteIp = "203.0.113.50"))
        }
        assertFailsWith<WebhookForbiddenException> {
            provider.verifyWebhook(WebhookPayload(queryParameters = signed, remoteIp = null))
        }

        // CIDR IP 216.127.71.44 -> accepted!
        val event = provider.verifyWebhook(WebhookPayload(queryParameters = signed, remoteIp = "216.127.71.44"))
        assertEquals(PaymentStatus.SUCCEEDED, event.status)
        assertEquals("pw_tx_100:0", event.eventKey)
        assertEquals("pay_100", event.referenceId)
        assertEquals("OK", provider.plainTextAck)
    }

    @Test
    fun `handles dashboard test probe, blocks test pingbacks in live mode, and maps refund and risk codes`() = runTest {
        val liveProvider = createPaymentProvider() as PaymentwallPaymentProvider

        // 1. Dashboard "Test pingback" button sends empty goodsid=/ref= signed with testSecretKey + is_test=1
        val probeParams = mapOf(
            "uid" to "goodsid",
            "goodsid" to "",
            "slength" to "",
            "speriod" to "",
            "type" to "0",
            "ref" to "",
            "is_test" to "1",
            "sign_version" to "3",
        )
        val signedProbe = probeParams + ("sign" to PaymentwallSignatures.signV3(probeParams, testSecretKey))
        val probeEvent = liveProvider.verifyWebhook(
            WebhookPayload(queryParameters = signedProbe, remoteIp = "174.36.96.66"),
        )
        assertNull(probeEvent.status)
        assertEquals("test_probe", probeEvent.metadata["pingbackEvent"])

        // 2. testSecretKey is NEVER accepted when is_test != "1"
        val forgedLiveWithTestSecret = mapOf(
            "uid" to "pay_live_1",
            "goodsid" to "pay_live_1",
            "type" to "0",
            "ref" to "pw_forged",
            "sign_version" to "3",
        ).let { it + ("sign" to PaymentwallSignatures.signV3(it, testSecretKey)) }
        assertFailsWith<WebhookSignatureException> {
            liveProvider.verifyWebhook(
                WebhookPayload(queryParameters = forgedLiveWithTestSecret, remoteIp = "174.36.96.66"),
            )
        }

        // 3. Full test pingback (is_test=1) sent to a live-mode provider is acknowledged (status = null) without granting!
        val testPingbackOnLive = mapOf(
            "uid" to "pay_live_1",
            "goodsid" to "pay_live_1",
            "type" to "0",
            "ref" to "pw_test_ref",
            "is_test" to "1",
            "sign_version" to "3",
        ).let { it + ("sign" to PaymentwallSignatures.signV3(it, testSecretKey)) }
        val ignoredTest = liveProvider.verifyWebhook(
            WebhookPayload(queryParameters = testPingbackOnLive, remoteIp = "174.36.96.66"),
        )
        assertNull(ignoredTest.status)
        assertContains(ignoredTest.eventKey, "ignored-mode")

        // 4. Chargeback/refund (type=2), subscription cancellation (type=12), expiry (type=13), failure (type=14), risk accepted (type=201)
        fun verifyType(typeCode: Int): Pair<PaymentStatus?, String?> {
            val p = mapOf(
                "uid" to "pay_200",
                "goodsid" to "pay_200",
                "type" to typeCode.toString(),
                "ref" to "pw_ref_200",
                "sign_version" to "3",
            ).let { it + ("sign" to PaymentwallSignatures.signV3(it, secretKey)) }
            val ev = kotlinx.coroutines.runBlocking {
                liveProvider.verifyWebhook(WebhookPayload(formParameters = p, remoteIp = "174.36.92.187"))
            }
            return ev.status to ev.metadata["pingbackEvent"]
        }

        assertEquals(PaymentStatus.REFUNDED to "negative", verifyType(2))
        assertEquals(null to "subscription_cancelled", verifyType(12))
        assertEquals(PaymentStatus.EXPIRED to "subscription_expired", verifyType(13))
        assertEquals(PaymentStatus.FAILED to "subscription_payment_failed", verifyType(14))
        assertEquals(null to "risk_under_review", verifyType(200))
        assertEquals(PaymentStatus.SUCCEEDED to "risk_accepted", verifyType(201))
        assertEquals(PaymentStatus.REFUNDED to "risk_declined", verifyType(202))
        assertEquals(PaymentStatus.REFUNDED to "authorization_voided", verifyType(203))
    }
}
