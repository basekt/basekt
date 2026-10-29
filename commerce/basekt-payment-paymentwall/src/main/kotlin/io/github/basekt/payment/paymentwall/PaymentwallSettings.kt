package io.github.basekt.payment.paymentwall

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Billing interval unit for recurring Paymentwall Digital Goods subscriptions (`ag_period_type`).
 *
 * @property wireName lowercase wire value expected by Paymentwall (`day`, `week`, `month`, `year`).
 */
@Serializable
public enum class PaymentwallPeriodType(public val wireName: String) {
    /** Daily billing period (`"day"`). */
    @SerialName("day")
    DAY("day"),

    /** Weekly billing period (`"week"`). */
    @SerialName("week")
    WEEK("week"),

    /** Monthly billing period (`"month"`). */
    @SerialName("month")
    MONTH("month"),

    /** Yearly billing period (`"year"`). */
    @SerialName("year")
    YEAR("year"),
}

/**
 * Recurring billing schedule for a Paymentwall subscription checkout (`ag_type = "subscription"`).
 *
 * @property periodLength positive number of intervals per billing cycle (`ag_period_length`).
 * @property periodType unit of each billing interval (`ag_period_type`).
 */
@Serializable
public data class PaymentwallSubscriptionSpec(
    public val periodLength: Int = 1,
    public val periodType: PaymentwallPeriodType = PaymentwallPeriodType.MONTH,
) {
    init {
        require(periodLength >= 1) { "periodLength ($periodLength) must be >= 1." }
    }
}

/**
 * Settings for [PaymentwallPaymentProvider]. Build from `ApplicationConfig` in the host application.
 *
 * @property id provider identifier (`"paymentwall"` by default).
 * @property projectKey Paymentwall project application key (`key` widget parameter).
 * @property secretKey Paymentwall primary secret key used for Signature v3 widget signing and pingback verification.
 * @property testSecretKey optional secondary secret key accepted **only** on test pingbacks (`is_test=1`).
 * @property widgetCode Paymentwall widget code (`"p1_1"` by default).
 * @property supportedCurrencies uppercase ISO currency codes accepted by this project.
 * @property testMode when `true`, appends `evaluation=1` to checkout URLs and accepts only `is_test=1` pingbacks; when `false`, ignores `is_test=1` pingbacks so test events never grant live payments.
 * @property enforceIpAllowlist when `true` (`true` by default), rejects pingbacks whose `remoteIp` is outside [PaymentwallIpAllowlist] (and [additionalAllowedIps]) with `WebhookForbiddenException`.
 * @property additionalAllowedIps optional extra sender IPs permitted alongside [PaymentwallIpAllowlist] when `enforceIpAllowlist` is enabled.
 * @property defaultSuccessUrl fallback `success_url` redirect when `CreatePaymentRequest.successUrl` is `null`.
 * @property defaultSubscription when non-null, creates recurring subscription checkouts (`ag_type=subscription`) by default unless overridden per goods id.
 * @property subscriptionsByGoodsId recurring subscription schedules keyed by `goodsId` (`CreatePaymentRequest.metadata["goodsId"]` or `referenceId`).
 * @property fixedCheckoutBaseUrl Paymentwall Digital Goods one-time checkout endpoint (`https://api.paymentwall.com/api/ps` by default).
 * @property subscriptionCheckoutBaseUrl Paymentwall Digital Goods recurring subscription endpoint (`https://api.paymentwall.com/api/subscription` by default).
 */
public data class PaymentwallSettings(
    public val id: String = "paymentwall",
    public val projectKey: String,
    public val secretKey: String,
    public val testSecretKey: String? = null,
    public val widgetCode: String = "p1_1",
    public val supportedCurrencies: Set<String> = setOf("USD", "PHP", "EUR", "GBP", "SGD", "JPY", "KRW"),
    public val testMode: Boolean = false,
    public val enforceIpAllowlist: Boolean = true,
    public val additionalAllowedIps: Set<String> = emptySet(),
    public val defaultSuccessUrl: String? = null,
    public val defaultSubscription: PaymentwallSubscriptionSpec? = null,
    public val subscriptionsByGoodsId: Map<String, PaymentwallSubscriptionSpec> = emptyMap(),
    public val fixedCheckoutBaseUrl: String = "https://api.paymentwall.com/api/ps",
    public val subscriptionCheckoutBaseUrl: String = "https://api.paymentwall.com/api/subscription",
) {
    /** Normalized uppercase currency codes accepted by this provider. */
    public val normalizedCurrencies: Set<String> = supportedCurrencies.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()

    init {
        require(id.isNotBlank()) { "Paymentwall provider id must not be blank." }
        require(projectKey.isNotBlank()) { "Paymentwall projectKey must not be blank." }
        require(secretKey.isNotBlank()) { "Paymentwall secretKey must not be blank." }
        require(widgetCode.isNotBlank()) { "Paymentwall widgetCode must not be blank." }
        require(normalizedCurrencies.isNotEmpty()) { "Paymentwall supportedCurrencies must not be empty." }
        require(fixedCheckoutBaseUrl.startsWith("http://") || fixedCheckoutBaseUrl.startsWith("https://")) {
            "fixedCheckoutBaseUrl '$fixedCheckoutBaseUrl' must start with http:// or https://."
        }
        require(subscriptionCheckoutBaseUrl.startsWith("http://") || subscriptionCheckoutBaseUrl.startsWith("https://")) {
            "subscriptionCheckoutBaseUrl '$subscriptionCheckoutBaseUrl' must start with http:// or https://."
        }
    }
}
