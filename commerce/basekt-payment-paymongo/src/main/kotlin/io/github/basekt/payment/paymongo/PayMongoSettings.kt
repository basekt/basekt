package io.github.basekt.payment.paymongo

import io.github.ronjunevaldoz.paymongo.models.resource.PaymentType
import kotlin.time.Clock

/**
 * Which PayMongo checkout endpoint [PayMongoPaymentProvider] uses when creating sessions.
 */
public enum class PayMongoFlow {
    /** `/v2/checkout_sessions` (deferred flow, recommended by PayMongo for new integrations). */
    CHECKOUT_SESSION_V2,

    /** `/v1/checkout_sessions` (classic flow with upfront PaymentIntent). */
    CHECKOUT_SESSION_V1,

    /** `/v1/payment_links` (shareable payment link without redirect URLs; supports link refunds). */
    PAYMENT_LINK,
}

private val DISALLOWED_CHECKOUT_TYPES = mapOf(
    "maya" to "Use 'paymaya' instead of 'maya' for PayMongo checkout sessions.",
    "atome" to "'atome' is only supported on Payment Intents/Methods, not PayMongo checkout sessions.",
)

/**
 * Settings for [PayMongoPaymentProvider]. Build from `ApplicationConfig` in the host application.
 *
 * @property id provider identifier (`"paymongo"` by default).
 * @property secretKey PayMongo secret API key (`sk_test_...` or `sk_live_...`).
 * @property webhookSecretKey PayMongo webhook signing secret (`whsec_...`), required when verifying webhooks.
 * @property flow which PayMongo resource [PayMongoPaymentProvider.createSession] creates.
 * @property paymentMethodTypes wire names of enabled checkout methods (`gcash`, `paymaya`, `qrph`, `card` by default).
 * @property defaultSuccessUrl fallback redirect URL after payment when `CreatePaymentRequest.successUrl` is null.
 * @property defaultCancelUrl fallback redirect URL on cancellation when `CreatePaymentRequest.cancelUrl` is null.
 * @property statementDescriptor descriptor shown on customer statements (`"Payment"` by default).
 * @property signatureHeaderName HTTP header carrying PayMongo's webhook signature (`"Paymongo-Signature"` by default).
 * @property signatureToleranceSeconds maximum age/skew of a webhook's `t=` timestamp in seconds (`300` by default; `0` disables timestamp checking).
 * @property clock clock used to check webhook timestamp freshness.
 */
public data class PayMongoSettings(
    public val id: String = "paymongo",
    public val secretKey: String,
    public val webhookSecretKey: String? = null,
    public val flow: PayMongoFlow = PayMongoFlow.CHECKOUT_SESSION_V2,
    public val paymentMethodTypes: List<String> = listOf("gcash", "paymaya", "qrph", "card"),
    public val defaultSuccessUrl: String? = null,
    public val defaultCancelUrl: String? = null,
    public val statementDescriptor: String = "Payment",
    public val signatureHeaderName: String = "Paymongo-Signature",
    public val signatureToleranceSeconds: Long = 300L,
    public val clock: Clock = Clock.System,
) {
    init {
        require(id.isNotBlank()) { "PayMongo provider id must not be blank." }
        require(secretKey.isNotBlank()) { "PayMongo secretKey must not be blank." }
        require(signatureToleranceSeconds >= 0) { "signatureToleranceSeconds must be >= 0." }
        if (flow != PayMongoFlow.PAYMENT_LINK) {
            require(paymentMethodTypes.isNotEmpty()) { "paymentMethodTypes must not be empty for PayMongo checkout sessions." }
            for (method in paymentMethodTypes) {
                val hint = DISALLOWED_CHECKOUT_TYPES[method]
                require(hint == null) { "Invalid PayMongo checkout payment method '$method': $hint" }
                requireNotNull(PaymentType.fromValue(method)) {
                    "Unknown PayMongo payment method '$method'."
                }
            }
        }
    }

    internal val resolvedMethodTypes: List<PaymentType>
        get() = paymentMethodTypes.map { PaymentType.fromValue(it)!! }
}
