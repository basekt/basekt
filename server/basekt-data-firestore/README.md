# basekt-data-firestore

A `DataSource` over Cloud Firestore: one collection per resource, document ids as record ids, Firestore transactions with nested rollback, aggregation counts, conditional writes and sequences. It reads collections another program wrote as they are.

```kotlin
dependencies { implementation("io.github.basekt:basekt-data-firestore:0.3.0-SNAPSHOT") }
```

In the latest `main`, published as a snapshot until 0.3.0 is released: see [Getting started](https://basekt.github.io/basekt/getting-started/#snapshots).

Builds on: basekt-data.

## Use

```kotlin
val firestore = FirestoreOptions.getDefaultInstance().service   // or FirestoreClient.getFirestore() from the Admin SDK
val data = FirestoreDataSource(
    firestore, schema,
    FirestoreMapping(collections = mapOf("questions" to "question")),
    maxScan = 0,        // only queries Firestore answers on its own
)
```

## Good to know

- No `Aggregating`: Firestore can't group records.
- Firestore filters equalities, `in`, an `or` of equalities, ranges and `!=`, and sorts by fields every match has. The rest (`contains`, `not`, `isNull`, nulls-last sorts, decimals stored as strings) is checked in memory, reading at most `maxScan` documents before it throws `UnsupportedQueryException`.
- A query that needs a composite index Firestore doesn't have throws `MissingIndexException`, with Firestore's link to create it.
- A transaction block may run again after a conflict. Its writes through the source land once; anything else it does happens once per run.
- Instants are stored as timestamps, which hold microseconds.

## Documentation

- [Adapters](https://basekt.github.io/basekt/adapters/#cloud-firestore)
- [All modules](../../README.md#modules)
