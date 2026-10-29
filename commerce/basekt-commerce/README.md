# basekt-commerce

An optional umbrella over the modules for selling online. Depend on it to get them all, or on one module alone.

```kotlin
dependencies { implementation("io.github.basekt:basekt-commerce:0.3.0-SNAPSHOT") }
```

In the latest `main`, published as a snapshot until 0.3.0 is released: see [Getting started](https://basekt.github.io/basekt/getting-started/#snapshots).

Builds on: basekt-storefront, basekt-payment, basekt-payments.

## Use

```kotlin
dependencies {
    implementation("io.github.basekt:basekt-commerce:VERSION")
    implementation("io.github.basekt:basekt-payment-paymongo:VERSION")   // or -paymentwall, -lemonsqueezy, -kaia
}
```

## Good to know

- Pulls in `basekt-storefront`, `basekt-payment` and `basekt-payments`; add the payment adapter(s) you use (`basekt-payment-paymongo`, `basekt-payment-paymentwall`, `basekt-payment-lemonsqueezy`, `basekt-payment-kaia`, or `basekt-payment-memory` for tests). Holds and customer accounts are planned, each as its own module.

## Documentation

- [Storefront](https://basekt.github.io/basekt/storefront/)
- [Payments](https://basekt.github.io/basekt/payments/)
- [All modules](../../README.md#modules)
