# basekt-data-mongo

A `DataSource` over MongoDB: one collection per resource, ObjectId ids and references, transactions with nested rollback, `migrate()` for indexes, and every capability. It reads collections another program wrote as they are.

```kotlin
dependencies { implementation("io.github.basekt:basekt-data-mongo:0.3.0-SNAPSHOT") }
```

In the latest `main`, published as a snapshot until 0.3.0 is released: see [Getting started](https://basekt.github.io/basekt/getting-started/#snapshots).

Builds on: basekt-data.

## Use

```kotlin
val client = MongoClient.create(System.getenv("MONGODB_URI"))
val data = MongoDataSource(
    client, "app", schema,
    MongoMapping(
        collectionPrefix = "content_",
        objectIds = mapOf("questions" to setOf("id", "subjectId")),
    ),
)
data.migrate()      // creates the indexes the schema wants; nothing else changes
```

## Good to know

- Needs MongoDB 5.0 or later, and a replica set (a single node will do) for transactions.
- Instants are stored as dates, which hold milliseconds. A sorted list is read through an aggregation.
- A transaction block may run again after a write conflict, so it must be safe to repeat.

## Documentation

- [Adapters](https://basekt.github.io/basekt/adapters/)
- [All modules](../../README.md#modules)
