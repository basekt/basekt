# basekt-payment-paymentwall

A `PaymentProvider` and `WebhookVerifiable` adapter for [Paymentwall](https://docs.paymentwall.com/) Digital Goods (`/api/ps`) and Subscriptions (`/api/subscription`), with Signature v3 (`SHA-256`) widget URL signing, sender IP allowlist enforcement, and plain-text `"OK"` pingback responses.

```kotlin
dependencies { implementation("io.github.basekt:basekt-payment-paymentwall:0.3.0-SNAPSHOT") }
```

In the latest `main`, published as a snapshot until 0.3.0 is released: see [Getting started](https://basekt.github.io/basekt/getting-started/#snapshots).

Builds on: basekt-payment, ktor-http.

## Use

```kotlin
val paymentwall = PaymentwallPaymentProvider(
    PaymentwallSettings(
        projectKey = config.property("paymentwall.projectKey").getString(),
        secretKey = config.property("paymentwall.secretKey").getString(),
        testSecretKey = config.propertyOrNull("paymentwall.testSecretKey")?.getString(),
        defaultSuccessUrl = config.property("paymentwall.successUrl").getString(),
    ),
)
```

## Good to know

- Signs widget URLs locally with Paymentwall Signature v3 (`SHA-256`) — both one-time `ag_type=fixed` checkouts and recurring `ag_type=subscription` intervals (`day`, `week`, `month`, `year`).
- Enforces Paymentwall's documented pingback sender IPv4 allowlist (`174.36.92.186`, `174.36.96.66`, `174.36.92.187`, `174.36.92.192`, `174.37.14.28`, `216.127.71.0/24`) by default, rejecting spoofed senders with `403 Forbidden`.
- Handles both `GET` query pingbacks and `POST` `application/x-www-form-urlencoded` pingbacks, preserves empty `key=` parameters during Signature v3 checks (so Paymentwall's dashboard "Test pingback" button gets `200 OK` `"OK"`), and blocks `is_test=1` pingbacks from settling live sessions.

## Documentation

- [Payments](https://basekt.github.io/basekt/payments/)
- [All modules](../../README.md#modules)
