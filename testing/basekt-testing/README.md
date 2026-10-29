# basekt-testing

The `DataSourceContract`, `BlobStoreContract` and `PaymentProviderContract` suites every adapter runs, and `testApplication` fixtures.

```kotlin
dependencies { implementation("io.github.basekt:basekt-testing:0.2.0") }
```

## Use

```kotlin
class MyDataSourceContract : DataSourceContract() {
    override fun createDataSource(): DataSource = MyDataSource(/* a new, empty one */)
}
```

## Good to know

- A data source that passes `DataSourceContract` behaves like the others on nulls, sorting, cursors, transactions and every capability it offers.

## Documentation

- [Adapters](https://basekt.github.io/basekt/adapters/)
- [Extending BaseKt](https://basekt.github.io/basekt/extending/)
- [All modules](../../README.md#modules)
