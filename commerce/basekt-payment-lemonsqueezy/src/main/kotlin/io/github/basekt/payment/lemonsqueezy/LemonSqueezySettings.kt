package io.github.basekt.payment.lemonsqueezy

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Settings for [LemonSqueezyPaymentProvider]. Build from `ApplicationConfig` in the host application.
 *
 * @property id provider identifier (`"lemonsqueezy"` by default).
 * @property apiKey Lemon Squeezy Bearer API key used for `POST /v1/checkouts`, `GET /v1/checkouts/{id}` and `GET /v1/subscriptions/{id}`.
 * @property storeId Lemon Squeezy store id (`data.relationships.store.data.id`); incoming webhooks for any other `store_id` are ignored.
 * @property webhookSecret HMAC-SHA256 signing secret configured on the Lemon Squeezy webhook (`X-Signature` header). Required when verifying webhooks.
 * @property defaultVariantId fallback Lemon Squeezy variant id used when neither `CreatePaymentRequest.metadata["variantId"]` nor [variantsByCurrency] matches.
 * @property variantsByCurrency optional map from uppercase currency code (such as `"USD"`) to Lemon Squeezy variant id.
 * @property supportedCurrencies uppercase currency codes accepted by this provider (`variantsByCurrency.keys` when non-empty, otherwise `setOf("USD")`).
 * @property sendCustomPrice when `true` (`true` by default), sends `attributes.custom_price` in minor units so server-stored record amounts drive checkout pricing; set `false` when using fixed catalog or recurring variant pricing.
 * @property testMode when `true`, creates test-mode checkouts and accepts only `test_mode=true` webhooks; when `false`, ignores `test_mode=true` webhooks so test events never grant live payments.
 * @property defaultSuccessUrl fallback `product_options.redirect_url` when `CreatePaymentRequest.successUrl` is `null`.
 * @property apiBaseUrl Lemon Squeezy JSON:API v1 base URL (`https://api.lemonsqueezy.com/v1` by default).
 * @property signatureHeaderName HTTP header carrying Lemon Squeezy's hex HMAC-SHA256 digest (`"X-Signature"` by default).
 * @property requestTimeout per-request HTTP timeout (`15s` by default).
 */
public data class LemonSqueezySettings(
    public val id: String = "lemonsqueezy",
    public val apiKey: String,
    public val storeId: String,
    public val webhookSecret: String? = null,
    public val defaultVariantId: String? = null,
    public val variantsByCurrency: Map<String, String> = emptyMap(),
    public val supportedCurrencies: Set<String> =
        if (variantsByCurrency.isNotEmpty()) variantsByCurrency.keys.map { it.trim().uppercase() }.toSet() else setOf("USD"),
    public val sendCustomPrice: Boolean = true,
    public val testMode: Boolean = false,
    public val defaultSuccessUrl: String? = null,
    public val apiBaseUrl: String = "https://api.lemonsqueezy.com/v1",
    public val signatureHeaderName: String = "X-Signature",
    public val requestTimeout: Duration = 15.seconds,
) {
    /** Normalized uppercase-currency-to-variant lookup map. */
    public val normalizedVariants: Map<String, String> = variantsByCurrency
        .map { (k, v) -> k.trim().uppercase() to v.trim() }
        .filter { (k, v) -> k.isNotEmpty() && v.isNotEmpty() }
        .toMap()

    /** Normalized uppercase currency codes accepted by this provider. */
    public val normalizedCurrencies: Set<String> = supportedCurrencies
        .map { it.trim().uppercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    init {
        require(id.isNotBlank()) { "Lemon Squeezy provider id must not be blank." }
        require(apiKey.isNotBlank()) { "Lemon Squeezy apiKey must not be blank." }
        require(storeId.isNotBlank()) { "Lemon Squeezy storeId must not be blank." }
        require(normalizedCurrencies.isNotEmpty()) { "Lemon Squeezy supportedCurrencies must not be empty." }
        require(signatureHeaderName.isNotBlank()) { "Lemon Squeezy signatureHeaderName must not be blank." }
        require(apiBaseUrl.startsWith("http://") || apiBaseUrl.startsWith("https://")) {
            "apiBaseUrl '$apiBaseUrl' must start with http:// or https://."
        }
    }
}
