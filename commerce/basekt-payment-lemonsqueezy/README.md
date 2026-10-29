# basekt-payment-lemonsqueezy

A `PaymentProvider` and `WebhookVerifiable` adapter for [Lemon Squeezy](https://docs.lemonsqueezy.com/api) over Ktor `HttpClient`: JSON:API (`application/vnd.api+json`) checkouts, subscription customer-portal URLs, and constant-time HMAC-SHA256 `X-Signature` webhook verification.

```kotlin
dependencies { implementation("io.github.basekt:basekt-payment-lemonsqueezy:0.3.0-SNAPSHOT") }
```

In the latest `main`, published as a snapshot until 0.3.0 is released: see [Getting started](https://basekt.github.io/basekt/getting-started/#snapshots).

Builds on: basekt-payment, ktor-client-core.

## Use

```kotlin
val lemonSqueezy = LemonSqueezyPaymentProvider(
    settings = LemonSqueezySettings(
        apiKey = config.property("lemonsqueezy.apiKey").getString(),
        storeId = config.property("lemonsqueezy.storeId").getString(),
        webhookSecret = config.property("lemonsqueezy.webhookSecret").getString(),
        defaultVariantId = config.property("lemonsqueezy.variantId").getString(),
    ),
    httpClient = sharedHttpClient, // optional: omit to create an internal CIO HttpClient
)
```

## Good to know

- Accepts an application-shared Ktor `HttpClient` (including `MockEngine` in unit tests) or creates its own `CIO` client when omitted.
- Sends `custom_price` in minor units (`sendCustomPrice = true` by default) so server-stored record amounts drive checkout pricing, or omits `custom_price` (`sendCustomPrice = false`) for fixed catalog/subscription variants.
- Verifies `X-Signature` hex HMAC-SHA256 digests in constant time and ignores webhooks whose `store_id` or `test_mode` does not match `LemonSqueezySettings`.
- Provides `customerPortalUrl(subscriptionId)` for redirecting subscribers to Lemon Squeezy's hosted billing portal.

## Documentation

- [Payments](https://basekt.github.io/basekt/payments/)
- [All modules](../../README.md#modules)
