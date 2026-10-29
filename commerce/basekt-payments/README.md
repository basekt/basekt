# basekt-payments

`install(BaseKtPayments)`: multi-provider payment sessions, signed webhooks and pingbacks, on-chain transfer confirmation, atomic replay protection, refunds and payouts over any `PaymentProvider` (`PayMongo`, `Paymentwall`, `Lemon Squeezy`, `Kaia USDT`, in-memory...).

```kotlin
dependencies { implementation("io.github.basekt:basekt-payments:0.3.0-SNAPSHOT") }
```

In the latest `main`, published as a snapshot until 0.3.0 is released: see [Getting started](https://basekt.github.io/basekt/getting-started/#snapshots).

Builds on: basekt-server, basekt-payment.

## Use

```kotlin
install(BaseKt) {
    dataSource = ExposedDataSource(database, schema)
    resources {
        paymentLedger(readers = setOf("admin"))
        resource("registrations") {
            decimal("fee") { required() }
            string("currency") { required() }
            enum("paymentState", listOf("unpaid", "paid", "refunded")) { required() }
            payable {
                amount("fee")
                currency("currency")
                statusField("paymentState", paid = "paid", refunded = "refunded")
            }
        }
    }
}
install(BaseKtPayments) {
    provider(PayMongoPaymentProvider(payMongoSettings))
    provider(KaiaUsdtPaymentProvider(kaiaSettings))
}
```

## Good to know

- Amounts and currencies are read from the target record on the server, never trusted from client request bodies.
- Every settled webhook event id and on-chain `<chainId>:<txHash>:<logIndex>` transfer key is written to `paymentReceipts` in the same `DataSource` transaction as the payment state change, preventing both webhook redelivery duplicates and cross-order `txHash` replay.

## Documentation

- [Payments](https://basekt.github.io/basekt/payments/)
- [All modules](../../README.md#modules)
