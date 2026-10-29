# basekt-payment-memory

An in-memory `PaymentProvider` for tests, samples and prototypes. Implements `WebhookVerifiable`, `OnChainConfirmable`, `Refundable` and `PayoutCapable`, and passes `PaymentProviderContract`.

```kotlin
dependencies { testImplementation("io.github.basekt:basekt-payment-memory:0.3.0-SNAPSHOT") }
```

In the latest `main`, published as a snapshot until 0.3.0 is released: see [Getting started](https://basekt.github.io/basekt/getting-started/#snapshots).

Builds on: basekt-payment.

## Use

```kotlin
val fakePayMongo = InMemoryPaymentProvider(id = "paymongo", supportedCurrencies = setOf("PHP"), webhookSecret = "whsec_test")
val fakeKaia = InMemoryPaymentProvider(id = "kaia-usdt", supportedCurrencies = setOf("USDT"), onChainMode = true)
```

## Documentation

- [Payments](https://basekt.github.io/basekt/payments/)
- [All modules](../../README.md#modules)
