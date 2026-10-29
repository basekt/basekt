package io.github.basekt.payment.paymentwall

import io.github.basekt.payment.CreatePaymentRequest
import io.github.basekt.payment.PaymentAmount
import io.github.basekt.payment.PaymentException
import io.github.basekt.payment.PaymentProvider
import io.github.basekt.payment.PaymentStatus
import io.github.basekt.payment.ProviderSession
import io.github.basekt.payment.VerifiedPaymentEvent
import io.github.basekt.payment.WebhookForbiddenException
import io.github.basekt.payment.WebhookPayload
import io.github.basekt.payment.WebhookSignatureException
import io.github.basekt.payment.WebhookVerifiable
import io.ktor.http.encodeURLQueryComponent
import io.ktor.http.parseUrlEncodedParameters
import java.math.BigDecimal
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Paymentwall Digital Goods and Subscriptions [PaymentProvider].
 *
 * - Builds Signature v3 (`SHA-256`) hosted widget URLs against `/api/ps` (one-time `ag_type=fixed`) or
 *   `/api/subscription` (recurring `ag_type=subscription`) without an outbound network round-trip.
 * - Verifies `GET` and `POST` (`application/x-www-form-urlencoded`) pingbacks against [PaymentwallIpAllowlist] and
 *   [PaymentwallSignatures], handles Paymentwall's dashboard "Test pingback" probes, prevents `is_test=1` pingbacks
 *   from settling live sessions, and declares [plainTextAck] = `"OK"` as required by Paymentwall's listener protocol.
 */
public class PaymentwallPaymentProvider(
    private val settings: PaymentwallSettings,
) : PaymentProvider, WebhookVerifiable {

    override val id: String get() = settings.id

    override val supportedCurrencies: Set<String> get() = settings.normalizedCurrencies

    override val plainTextAck: String = "OK"

    private val recentSessions = ConcurrentHashMap<String, ProviderSession>()

    override suspend fun createSession(request: CreatePaymentRequest): ProviderSession {
        val currency = request.amount.currency
        if (currency !in supportedCurrencies) {
            throw PaymentException(
                "Paymentwall provider '$id' does not support currency '$currency' (supported: ${supportedCurrencies.joinToString()}).",
            )
        }

        val uid = request.metadata["uid"]?.trim()?.takeIf { it.isNotEmpty() } ?: request.referenceId
        val goodsId = request.metadata["goodsId"]?.trim()?.takeIf { it.isNotEmpty() } ?: request.referenceId
        val summary = request.description?.trim()?.takeIf { it.isNotEmpty() } ?: "Payment ${request.referenceId}"
        val formattedAmount = formatWidgetAmount(request.amount)
        val subscription = resolveSubscription(goodsId, request.metadata)

        val params = TreeMap<String, String>()
        params["ag_external_id"] = goodsId
        params["ag_name"] = summary
        if (subscription != null) {
            params["ag_type"] = "subscription"
            params["ag_period_length"] = subscription.periodLength.toString()
            params["ag_period_type"] = subscription.periodType.wireName
            params["ag_recurring"] = "1"
        } else {
            params["ag_type"] = "fixed"
        }
        params["amount"] = formattedAmount
        params["currencyCode"] = currency
        request.metadata["email"]?.trim()?.takeIf { it.isNotEmpty() }?.let { params["email"] = it }
        if (settings.testMode) {
            params["evaluation"] = "1"
        }
        params["key"] = settings.projectKey
        params["sign_version"] = "3"
        (request.successUrl ?: settings.defaultSuccessUrl)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            params["success_url"] = it
        }
        params["uid"] = uid
        params["widget"] = settings.widgetCode
        if (uid != request.referenceId && goodsId != request.referenceId) {
            params["reference_id"] = request.referenceId
        }

        val signature = PaymentwallSignatures.signV3(params, settings.secretKey)
        val baseUrl = if (subscription != null) settings.subscriptionCheckoutBaseUrl else settings.fixedCheckoutBaseUrl
        val queryString = params.entries.joinToString("&") { (k, v) ->
            "${k.encodeURLQueryComponent(encodeFull = true)}=${v.encodeURLQueryComponent(encodeFull = true)}"
        }
        val separator = if ('?' in baseUrl) '&' else '?'
        val checkoutUrl = "$baseUrl$separator$queryString&sign=$signature"

        val externalId = "pw_${UUID.randomUUID().toString().replace("-", "")}"
        val session = ProviderSession(
            externalId = externalId,
            status = PaymentStatus.PENDING,
            amount = request.amount,
            checkoutUrl = checkoutUrl,
            expiresAt = request.expiresAt,
            metadata = request.metadata + mapOf(
                "referenceId" to request.referenceId,
                "uid" to uid,
                "goodsId" to goodsId,
            ),
        )
        if (recentSessions.size > 4_096) recentSessions.clear()
        recentSessions[externalId] = session
        return session
    }

    override suspend fun getSession(externalId: String): ProviderSession? = recentSessions[externalId]

    override suspend fun verifyWebhook(payload: WebhookPayload): VerifiedPaymentEvent {
        if (settings.enforceIpAllowlist) {
            val ip = payload.remoteIp?.trim()?.takeIf { it.isNotEmpty() }
            val allowed = ip != null && (PaymentwallIpAllowlist.isAllowed(ip) || ip in settings.additionalAllowedIps)
            if (!allowed) {
                throw WebhookForbiddenException(
                    "Paymentwall pingback rejected: sender IP '${ip ?: "unknown"}' is not in the Paymentwall IP allowlist.",
                )
            }
        }

        val params = LinkedHashMap<String, String>()
        params.putAll(payload.queryParameters)
        params.putAll(payload.formParameters)
        if (params.isEmpty() && payload.rawBody.isNotBlank() && !payload.rawBody.trimStart().startsWith("{")) {
            val parsed = payload.rawBody.parseUrlEncodedParameters()
            for ((key, values) in parsed.entries()) {
                params[key] = values.firstOrNull().orEmpty()
            }
        }

        val signVersion = params["sign_version"]
        if (signVersion != "3") {
            throw WebhookSignatureException(
                "Paymentwall pingback requires sign_version=3 (got '${signVersion ?: "missing"}').",
            )
        }
        val providedSign = params["sign"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: throw WebhookSignatureException("Paymentwall pingback is missing 'sign' parameter.")

        val isTestPingback = params["is_test"] == "1"
        val validPrimary = PaymentwallSignatures.verifyV3(params, settings.secretKey)
        val validSecondaryTest = !validPrimary &&
            isTestPingback &&
            !settings.testSecretKey.isNullOrBlank() &&
            PaymentwallSignatures.verifyV3(params, settings.testSecretKey)

        if (!validPrimary && !validSecondaryTest) {
            throw WebhookSignatureException("Paymentwall pingback signature mismatch.")
        }

        val typeCode = params["type"]?.trim()?.toIntOrNull()
            ?: throw WebhookSignatureException("Paymentwall pingback has missing or non-integer 'type' parameter.")

        val uid = params["uid"]?.takeIf { it.isNotBlank() }
        val goodsId = params["goodsid"]?.takeIf { it.isNotBlank() }
        val ref = params["ref"]?.takeIf { it.isNotBlank() }

        // Paymentwall's dashboard "Test pingback" button sends empty goodsid=/ref= with is_test=1: answer 200 "OK" with no state change.
        if (isTestPingback && (uid == null || goodsId == null || ref == null)) {
            return VerifiedPaymentEvent(
                eventKey = "probe:$typeCode:${providedSign.take(16)}",
                status = null,
                metadata = params + ("pingbackEvent" to "test_probe"),
            )
        }

        if (uid == null || goodsId == null || ref == null) {
            throw WebhookSignatureException("Paymentwall pingback requires non-blank 'uid', 'goodsid', and 'ref'.")
        }

        // Prevent test pingbacks from settling live sessions (and vice versa) while still returning 200 "OK".
        if (isTestPingback != settings.testMode) {
            return VerifiedPaymentEvent(
                eventKey = "ignored-mode:$ref:$typeCode",
                status = null,
                paymentReference = ref,
                metadata = params + ("pingbackEvent" to "mode_mismatch"),
            )
        }

        val (status, eventLabel) = when (typeCode) {
            TYPE_REGULAR -> PaymentStatus.SUCCEEDED to "regular"
            TYPE_GOODWILL -> PaymentStatus.SUCCEEDED to "goodwill"
            TYPE_NEGATIVE -> PaymentStatus.REFUNDED to "negative"
            TYPE_SUBSCRIPTION_CANCELLED -> null to "subscription_cancelled"
            TYPE_SUBSCRIPTION_EXPIRED -> PaymentStatus.EXPIRED to "subscription_expired"
            TYPE_SUBSCRIPTION_PAYMENT_FAILED -> PaymentStatus.FAILED to "subscription_payment_failed"
            TYPE_RISK_UNDER_REVIEW -> null to "risk_under_review"
            TYPE_RISK_REVIEWED_ACCEPTED -> PaymentStatus.SUCCEEDED to "risk_accepted"
            TYPE_RISK_REVIEWED_DECLINED -> PaymentStatus.REFUNDED to "risk_declined"
            TYPE_AUTHORIZATION_VOIDED -> PaymentStatus.REFUNDED to "authorization_voided"
            else -> null to "unknown_$typeCode"
        }

        val resolvedReferenceId = params["reference_id"]?.takeIf { it.isNotBlank() }
            ?: goodsId.takeIf { it.startsWith("pay_") }
            ?: uid

        return VerifiedPaymentEvent(
            eventKey = "$ref:$typeCode",
            status = status,
            referenceId = resolvedReferenceId,
            paymentReference = ref,
            metadata = params + ("pingbackEvent" to eventLabel),
        )
    }

    private fun resolveSubscription(
        goodsId: String,
        metadata: Map<String, String>,
    ): PaymentwallSubscriptionSpec? {
        settings.subscriptionsByGoodsId[goodsId]?.let { return it }
        val metaLength = metadata["periodLength"]?.trim()?.toIntOrNull()
        val metaTypeRaw = metadata["periodType"]?.trim()?.lowercase()
        if (metaLength != null && metaTypeRaw != null) {
            val periodType = PaymentwallPeriodType.entries.firstOrNull { it.wireName == metaTypeRaw }
                ?: throw PaymentException("Invalid Paymentwall periodType '$metaTypeRaw' (expected day, week, month or year).")
            if (metaLength < 1) {
                throw PaymentException("Invalid Paymentwall periodLength '$metaLength' (must be >= 1).")
            }
            return PaymentwallSubscriptionSpec(periodLength = metaLength, periodType = periodType)
        }
        return settings.defaultSubscription
    }

    private fun formatWidgetAmount(amount: PaymentAmount): String {
        val raw = BigDecimal(amount.rawUnits(), amount.decimals)
        val scale = if (amount.decimals == 0) 0 else maxOf(2, amount.decimals)
        return raw.setScale(scale).toPlainString()
    }

    private companion object {
        const val TYPE_REGULAR: Int = 0
        const val TYPE_GOODWILL: Int = 1
        const val TYPE_NEGATIVE: Int = 2
        const val TYPE_SUBSCRIPTION_CANCELLED: Int = 12
        const val TYPE_SUBSCRIPTION_EXPIRED: Int = 13
        const val TYPE_SUBSCRIPTION_PAYMENT_FAILED: Int = 14
        const val TYPE_RISK_UNDER_REVIEW: Int = 200
        const val TYPE_RISK_REVIEWED_ACCEPTED: Int = 201
        const val TYPE_RISK_REVIEWED_DECLINED: Int = 202
        const val TYPE_AUTHORIZATION_VOIDED: Int = 203
    }
}
