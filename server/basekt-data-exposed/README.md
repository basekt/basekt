# basekt-data-exposed

A `DataSource` over Exposed and JDBC: Postgres, MySQL and H2. One table per resource, keyset cursors, batched loading, savepoints, and every capability.

```kotlin
dependencies { implementation("io.github.basekt:basekt-data-exposed:0.2.0") }
```

Builds on: basekt-data.

## Use

```kotlin
val data = ExposedDataSource(database, shopSchema)
val migration = data.migrate()      // creates tables, adds columns and indexes, never drops anything
migration.notes.forEach { log.warn("Left alone: $it") }
```

## Good to know

- `migrate()` only makes changes that keep every row, and reports what it found but left alone.
- A resource can be kept in a table that already exists, with its own table and column names, key and column types,
  and optionally read-only: `TableMapping(tables = mapOf("members" to MappedTable("member", ...)))`. `migrate()` never
  changes such a table, and reports in `notes` what doesn't match the schema.
- Its contract suite also runs on Postgres: see [Building](https://basekt.github.io/basekt/building/).

## Documentation

- [Adapters](https://basekt.github.io/basekt/adapters/)
- [All modules](../../README.md#modules)
