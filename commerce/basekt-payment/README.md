# basekt-payment

The `PaymentProvider` SPI: sessions, exact multi-decimal amounts, and optional capabilities for signed webhooks, on-chain transfer verification, refunds and payouts, with no Ktor dependency.

```kotlin
dependencies { implementation("io.github.basekt:basekt-payment:0.3.0-SNAPSHOT") }
```

In the latest `main`, published as a snapshot until 0.3.0 is released: see [Getting started](https://basekt.github.io/basekt/getting-started/#snapshots).

## Use

Implement `PaymentProvider`, plus any of `WebhookVerifiable`, `OnChainConfirmable`, `Refundable` and `PayoutCapable` that the provider supports, and run `PaymentProviderContract` from `basekt-testing`. Most applications install an existing adapter (`basekt-payment-paymongo`, `basekt-payment-kaia`, `basekt-payment-memory`) with `BaseKtPayments` or call it directly.

## Good to know

- `PaymentAmount` keeps minor/base units as canonical decimal digits with explicit `decimals`, so 2-decimal fiat (`PHP`), 6-decimal stablecoins (`USDT`) and 18-decimal native gas tokens (`KAIA`) never overflow `Long` or lose precision.
- Every adapter function is `suspend` and safe to call concurrently.

## Documentation

- [Payments](https://basekt.github.io/basekt/payments/)
- [All modules](../../README.md#modules)
