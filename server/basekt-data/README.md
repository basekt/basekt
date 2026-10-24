# basekt-data

The `DataSource` SPI: queries and filters that no database owns, keyset cursor pagination, batched loading and transactions, plus optional capabilities (`Counting`, `Sequencing`, `ConditionalWrites`, `Aggregating`) and a typed `Repository`. Adapters implement it, and the server depends only on it.

```kotlin
dependencies { implementation("io.github.basekt:basekt-data:0.2.0") }
```

Builds on: basekt-schema.

## Use

```kotlin
val page = source.list(
    products,
    Query(
        filter = Filter.Compare("price", Comparison.GTE, Value.Decimal.of("10")),
        sort = listOf(Sort("name")),
        limit = 20,
    ),
)
val next = page.nextCursor   // pass it as `cursor` for the following page
```

## Good to know

- Nulls never match a comparison, `Not` is the complement, and nulls sort last ascending: every adapter follows these.
- To write an adapter, run `DataSourceContract` from `basekt-testing`.

## Documentation

- [Adapters](https://basekt.github.io/basekt/adapters/)
- [Aggregates and reports](https://basekt.github.io/basekt/aggregates-reports/)
- [All modules](../../README.md#modules)
