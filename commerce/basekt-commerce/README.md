# basekt-commerce

An optional umbrella over the modules for selling online. Depend on it to get them all, or on one module alone.

```kotlin
dependencies { implementation("io.github.basekt:basekt-commerce:0.3.0-SNAPSHOT") }
```

In the latest `main`, published as a snapshot until 0.3.0 is released: see [Getting started](https://basekt.github.io/basekt/getting-started/#snapshots).

Builds on: basekt-storefront.

## Use

```kotlin
dependencies { implementation("io.github.basekt:basekt-commerce:VERSION") }
```

## Good to know

- Today it pulls in `basekt-storefront`. Payments, holds, idempotency and customers are planned, each as its own module.

## Documentation

- [Storefront](https://basekt.github.io/basekt/storefront/)
- [All modules](../../README.md#modules)
