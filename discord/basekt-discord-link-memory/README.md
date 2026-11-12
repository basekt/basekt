# basekt-discord-link-memory

In-memory `PendingLinkStore` and `LinkedAccounts` for tests, samples and prototypes. Both pass the contract suites in `basekt-testing`.

```kotlin
dependencies { testImplementation("io.github.basekt:basekt-discord-link-memory:0.3.0-SNAPSHOT") }
```

In the latest `main`, published as a snapshot until 0.3.0 is released: see [Getting started](https://basekt.github.io/basekt/getting-started/#snapshots).

Builds on: basekt-discord-link.

## Use

```kotlin
val linker = AccountLinker(InMemoryPendingLinkStore(), InMemoryLinkedAccounts())
```

## Documentation

- [Discord account link](https://basekt.github.io/basekt/discord-link/)
- [All modules](../../README.md#modules)
