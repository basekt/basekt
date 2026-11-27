# basekt-payment-hitpay

A `PaymentProvider` for [HitPay](https://docs.hitpayapp.com/) over Ktor `HttpClient`: hosted payment requests, refunds, and constant-time HMAC-SHA256 `Hitpay-Signature` webhook verification. It implements `WebhookVerifiable` and `Refundable`. HitPay takes GCash, QRPh, GrabPay, ShopeePay, BillEase and cards in the Philippines, PayNow and cards in Singapore, and more in Malaysia.

```kotlin
dependencies { implementation("io.github.basekt:basekt-payment-hitpay:0.19.0") }
```

New in 0.19.0, which isn't released yet. Until it is, use the snapshot: see [Getting started](https://basekt.github.io/basekt/getting-started/#snapshots).

Builds on: basekt-payment, ktor-client-core.

## Use

```kotlin
val hitpay = HitPayPaymentProvider(
    settings = HitPaySettings(
        apiKey = config.property("hitpay.apiKey").getString(),
        webhookSalt = config.property("hitpay.webhookSalt").getString(),
        sandbox = config.propertyOrNull("hitpay.sandbox")?.getString().toBoolean(),
        paymentMethods = listOf("gcash", "qrph_netbank"), // optional: empty offers every method on the account
        defaultSuccessUrl = "https://shop.example/thanks",
    ),
    httpClient = sharedHttpClient, // optional: omit to create an internal CIO HttpClient
)
```

Then register a webhook in HitPay's dashboard (Developers, Webhooks) pointing at `https://<your host>/payments/webhooks/hitpay`, with the `payment_request.completed` and `payment_request.failed` events (and `charge.updated` to hear about refunds made in the dashboard). Its salt is `webhookSalt`.

## Good to know

- The caller's `referenceId` goes into `reference_number`, so a webhook finds its session even before the payment request id is stored. The buyer's `email`, `name` and `phone` from the request metadata go to HitPay's own fields; other metadata goes into `metadata`.
- `amount` is sent in major units at the currency's scale (`450.00`), as HitPay expects. `CreatePaymentRequest.expiresAt` becomes `expires_after` in whole minutes, rounded up.
- `verifyWebhook` checks `Hitpay-Signature` over the exact raw body. `payment_request` events (`completed`, `failed`) and `charge` events settle sessions; payouts, invoices, orders and transfers are informational. Without HitPay's `Hitpay-Event-Object` header, the payload's shape decides.
- A payment request's `webhook` field is deprecated by HitPay, so it isn't sent, and its form-encoded `hmac` callbacks aren't verified.
- `refund` uses the payment id the webhook stored, or reads it from the payment request when there is none.
- HitPay's timestamps usually carry no time zone, so `occurredAt` is left empty rather than guessed.
- `HitPaySettings.toString()` leaves out the API key and the salt.

## Documentation

- [Payments](https://basekt.github.io/basekt/payments/)
- [All modules](../../README.md#modules)
