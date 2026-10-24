# basekt-data-memory

An in-memory `DataSource`, for tests, samples and prototypes. It offers every capability and passes the contract suite.

```kotlin
dependencies { implementation("io.github.basekt:basekt-data-memory:0.2.0") }
```

Builds on: basekt-data.

## Use

```kotlin
val source = InMemoryDataSource()
```

## Good to know

- Nothing is kept after the process ends.
- It is the fastest way to test a schema, a hook or an action.

## Documentation

- [Adapters](https://basekt.github.io/basekt/adapters/)
- [All modules](../../README.md#modules)
