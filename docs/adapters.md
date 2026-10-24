# Adapters

Data, auth and storage each sit behind a small SPI. A new backend is a new module, not a fork, and the server depends
only on the SPI.

## Data

The `DataSource` SPI (`basekt-data`) covers queries, writes, transactions, and optional capabilities such as
`Counting`. Set the adapter in `install(BaseKt)`:

```kotlin
dataSource = ExposedDataSource(database, schema)  // or InMemoryDataSource(), or any other adapter
```

| Module | What it is |
|---|---|
| `basekt-data-memory` | in-memory adapter, for tests, samples and prototypes |
| `basekt-data-exposed` | Exposed/JDBC adapter: Postgres, MySQL, H2. Keyset cursors, one-query `getMany`, savepoints |
| `basekt-data-mongo` | MongoDB adapter: a collection per resource, ObjectId ids and references, transactions, indexes, and existing collections read as they are |

Some features need an optional capability of the data source. The in-memory, Exposed and MongoDB adapters offer all of them:

| Capability | Used by |
|---|---|
| `Sequencing` | [sequence numbers](automatic-fields.md#sequences); the Exposed adapter keeps counters in a `basekt_sequences` table |
| `ConditionalWrites` | [conditional writes](concurrent-writes.md#conditional-writes); the Exposed adapter does one `UPDATE … WHERE` |
| `Aggregating` | [aggregates and reports](aggregates-reports.md); the Exposed adapter runs one `SELECT … GROUP BY` |

Every adapter runs the `DataSourceContract` suite from `basekt-testing`.

### MongoDB

`basekt-data-mongo` stores each resource as a collection, so BaseKt can run on a MongoDB you already have:

```kotlin
val client = MongoClient.create(System.getenv("MONGODB_URI"))
val data = MongoDataSource(client, "app", schema)
data.migrate()   // creates the indexes the schema wants; nothing else changes
```

It offers every capability above, and passes the same `DataSourceContract` suite as the others, on a real MongoDB 8.0
replica set. It needs MongoDB 5.0 or later (time buckets in aggregates use `$dateTrunc`) and a replica set, which a
single node can be, for transactions.

**Documents.** A document holds `_id` and one value per field under the field's name, `null` when unset: strings, enums,
references and file keys as strings, `int` as int32, `long` as int64, decimals as Decimal128 (exact), booleans, and
instants as dates. A date holds milliseconds, so an instant's finer digits are dropped.

**Collections that already exist** are read as they are, which is how BaseKt can describe documents another program
wrote. Numbers may be any numeric type, a missing field is `null`, and what the schema doesn't name is never touched by
an update. `MongoMapping` says where things are:

```kotlin
val mapping = MongoMapping(
    collectionPrefix = "content_",                       // or `collections = mapOf("questions" to "question")`
    objectIds = mapOf("questions" to setOf("id", "subjectId")),   // stored as ObjectIds, read as hex strings
    indexes = listOf(IndexSpec("questions", "subjectId", "isActive")),
)
```

An id or reference named in `objectIds` is an ObjectId when its value is 24 hexadecimal digits and a string otherwise;
new ids are ObjectId hex strings, so they fit.

**Transactions** are MongoDB's. A transient failure, such as two transactions writing the same document, runs the block
again for up to `transactionRetryTimeout` (30 seconds), so a block must be safe to repeat. MongoDB has no savepoints, so
a nested transaction keeps an undo log of its own writes and plays it back if it fails while the outer one goes on. A
write MongoDB itself rejects, such as a duplicate id, aborts the whole transaction.

**Sorting** puts nulls last when ascending and first when descending, as the other adapters do. MongoDB doesn't do that
itself, so a sorted list is read through an aggregation and can't use an index for its order; a list without a sort is in
id order and can.

**`migrate()`** creates, per resource, a unique index for each `unique()` field (over the documents where it is set, so
many records may leave it null), an index on `(reference, _id)` for each reference field, and the indexes you list. It
never changes or drops an index or touches a document. What it can't create, such as a unique index while two documents
share a value, or keys that already have an index under another name, is listed in `notes`.

### Upgrading an Exposed database

`migrate()` brings a database up to the schema at startup, in one transaction, and reports what it did:

```kotlin
val migration = dataSource.migrate()                     // in place of createTables()
migration.applied.forEach { log.info("Migrated: $it") }  // the SQL it ran
migration.notes.forEach { log.warn("Left alone: $it") }  // what it found but didn't change
```

It only makes changes that keep every row:
- it creates missing tables and adds the columns of new fields. Every column is nullable, so existing rows get null,
  and filling them in is the application's job;
- it widens text columns that became too short, such as an enum given a longer value or a raised length rule;
- it creates missing indexes. A field made `unique()` gets its unique index, and while two rows still share a value
  the index can't be built, so the migration fails.

It never drops, renames or narrows a column, nor changes a column's type. A column no field has any more is kept with
its data and named in `notes`. A renamed field is a new column, so copy the old values over yourself.

On Postgres a failed migration changes nothing. H2 and MySQL commit each schema change as it runs, so a failure there
can leave the changes made before it, and running `migrate()` again carries on from there.

## Auth

`basekt-auth` holds Ktor Authentication adapters (JWT, bearer), `PrincipalRoles` mapped to permissions, and typed 401
and 403:

```kotlin
install(Authentication) {
    baseKtJwt("jwt", JwtSettings.from(environment.config.config("auth.jwt")))   // basekt-auth
}
install(BaseKt) {
    identities = AuthIdentities("jwt")                 // roles from the JWT, checked by the permissions
    // ...
}
```

With JWTs, `PrincipalRoles.jwt(attributeClaims = listOf("clinic"))` reads [scope](permissions.md#record-scopes)
attributes from claims, and `JwtSettings.issue` writes them.

## Storage

The `BlobStore` SPI (`basekt-storage`) covers streamed puts and reads of files, byte ranges, and optional listing and
presigned URLs. [Files](files.md) keeps its buckets over any store.

| Module | What it is |
|---|---|
| `basekt-storage-memory`, `-local` | in-memory and local-filesystem stores, both passing the `BlobStoreContract` suite |
| `basekt-storage-s3` | an S3 store for AWS, MinIO, R2 and other S3-compatible services: Signature V4 with no SDK, multipart uploads, ranges and presigned URLs |

The S3 store is built with `S3BlobStore(S3Settings(endpoint, bucket, region, accessKey, secretKey))`.
