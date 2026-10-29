# basekt-payment-paymongo

A `PaymentProvider` over [`paymongo-kotlin`](https://github.com/ronjunevaldoz/KotlinPaymongo) (`1.3.0`) for Philippine payment rails: GCash, Maya (`paymaya`), QRPh, GrabPay, ShopeePay, Billease, direct online banking and cards. Implements `WebhookVerifiable` and `Refundable`, with no Ktor server dependency.

```kotlin
dependencies { implementation("io.github.basekt:basekt-payment-paymongo:0.3.0-SNAPSHOT") }
```

In the latest `main`, published as a snapshot until 0.3.0 is released: see [Getting started](https://basekt.github.io/basekt/getting-started/#snapshots).

Builds on: basekt-payment.

## Use

```kotlin
val paymongo = PayMongoPaymentProvider(
    PayMongoSettings(
        secretKey = config.property("paymongo.secretKey").getString(),
        webhookSecretKey = config.property("paymongo.webhookSecretKey").getString(),
        paymentMethodTypes = listOf("gcash", "paymaya", "qrph", "card"),
        defaultSuccessUrl = "https://shop.example/thanks",
        defaultCancelUrl = "https://shop.example/cart",
    ),
)
```

## Good to know

- Checkout Sessions v2 (`PayMongoFlow.CHECKOUT_SESSION_V2`) is the default; switch to `PAYMENT_LINK` when no redirect URLs are needed.
- Checkout sessions accept `"paymaya"`, not `"maya"`; `PayMongoSettings` checks that up front.
- `verifyWebhook` checks `Paymongo-Signature` in constant time and enforces a configurable timestamp tolerance (`300` seconds by default).

## Documentation

- [Payments](https://basekt.github.io/basekt/payments/)
- [All modules](../../README.md#modules)
