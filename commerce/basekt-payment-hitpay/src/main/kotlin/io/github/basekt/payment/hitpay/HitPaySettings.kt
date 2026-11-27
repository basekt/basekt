package io.github.basekt.payment.hitpay

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val PAYMENT_METHOD = Regex("[a-z0-9_]+")

/**
 * Settings for [HitPayPaymentProvider]. Build from `ApplicationConfig` in the host application.
 *
 * @property id provider identifier (`"hitpay"` by default). It is also the last segment of the webhook route that
 *   `BaseKtPayments` serves (`/payments/webhooks/hitpay`).
 * @property apiKey the business API key from HitPay's dashboard, sent as `X-BUSINESS-API-KEY`.
 * @property webhookSalt the salt of the webhook endpoint registered in HitPay's dashboard, which signs every event in
 *   the `Hitpay-Signature` header. It is the webhook's own salt, not the API key's. Required to verify webhooks.
 * @property sandbox when `true`, talks to HitPay's sandbox (`https://api.sandbox.hit-pay.com/v1`); `false` (the default)
 *   talks to `https://api.hit-pay.com/v1`. Sandbox and live keys and salts differ, so a sandbox event never verifies on
 *   a live provider.
 * @property supportedCurrencies uppercase currency codes accepted (`setOf("PHP")` by default; a Singapore business uses
 *   `setOf("SGD")`, a Malaysian one `setOf("MYR")`).
 * @property paymentMethods HitPay payment method codes offered at checkout, such as `"gcash"`, `"qrph_netbank"` or
 *   `"shopee_pay"`. Empty (the default) offers every method enabled on the account.
 * @property defaultSuccessUrl fallback `redirect_url` when `CreatePaymentRequest.successUrl` is `null`. HitPay appends
 *   `reference` (the payment request id) and `status` to it.
 * @property sendEmail when `true`, HitPay emails the buyer a receipt. It needs the buyer's email, which
 *   `BaseKtPayments` passes from `payable { buyerEmail(...) }`.
 * @property apiBaseUrl the API base URL, chosen by [sandbox] unless set.
 * @property requestTimeout per-request HTTP timeout (`15s` by default).
 */
public data class HitPaySettings(
    public val id: String = "hitpay",
    public val apiKey: String,
    public val webhookSalt: String? = null,
    public val sandbox: Boolean = false,
    public val supportedCurrencies: Set<String> = setOf("PHP"),
    public val paymentMethods: List<String> = emptyList(),
    public val defaultSuccessUrl: String? = null,
    public val sendEmail: Boolean = false,
    public val apiBaseUrl: String = if (sandbox) SANDBOX_API_BASE_URL else LIVE_API_BASE_URL,
    public val requestTimeout: Duration = 15.seconds,
) {
    /** Normalized uppercase currency codes accepted by this provider. */
    public val normalizedCurrencies: Set<String> = supportedCurrencies
        .map { it.trim().uppercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    init {
        require(id.isNotBlank()) { "HitPay provider id must not be blank." }
        require(apiKey.isNotBlank()) { "HitPay apiKey must not be blank." }
        require(webhookSalt == null || webhookSalt.isNotBlank()) { "HitPay webhookSalt must not be blank when set." }
        require(normalizedCurrencies.isNotEmpty()) { "HitPay supportedCurrencies must not be empty." }
        val badMethods = paymentMethods.filterNot { PAYMENT_METHOD.matches(it) }
        require(badMethods.isEmpty()) {
            "HitPay payment methods ${badMethods.joinToString { "'$it'" }} are not method codes: a code is lower case " +
                "letters, digits and underscores, such as 'gcash' or 'qrph_netbank'."
        }
        require(apiBaseUrl.startsWith("http://") || apiBaseUrl.startsWith("https://")) {
            "apiBaseUrl '$apiBaseUrl' must start with http:// or https://."
        }
        require(requestTimeout.isPositive()) { "HitPay requestTimeout must be positive." }
    }

    /** Leaves [apiKey] and [webhookSalt] out, so settings can be logged. */
    override fun toString(): String =
        "HitPaySettings(id=$id, sandbox=$sandbox, supportedCurrencies=$normalizedCurrencies, " +
            "paymentMethods=$paymentMethods, apiBaseUrl=$apiBaseUrl, webhookSalt=${if (webhookSalt == null) "unset" else "set"})"

    /** HitPay's API base URLs. */
    public companion object {
        /** HitPay's live API (`https://api.hit-pay.com/v1`). */
        public const val LIVE_API_BASE_URL: String = "https://api.hit-pay.com/v1"

        /** HitPay's sandbox API (`https://api.sandbox.hit-pay.com/v1`). */
        public const val SANDBOX_API_BASE_URL: String = "https://api.sandbox.hit-pay.com/v1"
    }
}
