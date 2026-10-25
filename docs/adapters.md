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
| `basekt-data-firestore` | Cloud Firestore adapter: a collection per resource, document ids as record ids, Firestore transactions, aggregation counts, and existing collections read as they are |

Some features need an optional capability of the data source. The in-memory, Exposed and MongoDB adapters offer all of them;
the Firestore adapter offers all but `Aggregating`:

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

### Cloud Firestore

`basekt-data-firestore` stores each resource as a top-level collection, so a backend that keeps its records in
Firestore can serve them through BaseKt without moving them:

```kotlin
val firestore = FirestoreOptions.getDefaultInstance().service   // or FirestoreClient.getFirestore() from the Admin SDK
val data = FirestoreDataSource(firestore, schema, FirestoreMapping(collections = mapOf("questions" to "question")))
```

It offers `Counting` (Firestore's aggregation count), `Sequencing` and `ConditionalWrites`, and passes the same
`DataSourceContract` suite as the others, on the Firestore emulator. It doesn't offer `Aggregating`, since Firestore has
no group-by, so [aggregates and reports](aggregates-reports.md) need another adapter.

**Documents.** The record's id is the document id, and the document holds one value per field under the field's name,
`null` when unset: strings, enums, references and file keys as strings, `int` and `long` as integers, booleans, instants
as timestamps (microseconds, so finer digits are dropped), and decimals as strings (exact) or, with
`decimals = DecimalStorage.DOUBLE`, as doubles. New ids are Firestore's own random ids. An id Firestore can't use as a
document id (empty, with a slash, `.`, `..`, or `__like this__`) is never found, and a create with one is refused.

**Collections that already exist** are read as they are. A number may be an integer or a double, a decimal may be a
string or a number, a reference may be a document reference (read as its id), and a missing field is `null`. An update
keeps the fields the schema doesn't name. Filters compare references as strings, so they don't match a field that holds
document references.

**Queries.** Firestore itself answers equalities, `in` (up to 30 values), an `or` of equalities, ranges and `!=`, and it
orders a list when every sort key is a field that every matching record has: one the filter compares or requires to be
set, or one the schema makes `required()`. Firestore leaves documents without the field out of a sorted query and puts
nulls first, so only then does its order match BaseKt's. The rest is checked in memory, over the documents Firestore
returns, reading on until the page is full:

| Firestore can't | so the adapter |
|---|---|
| search text (`contains`), negate (`not`), or find a null or missing field (`isNull`) | checks the condition in memory |
| take a range or `!=` on a field the list isn't sorted by, since that makes Firestore sort by it | checks the condition in memory |
| take more than one `!=` (or "is set") condition | checks the others in memory |
| sort with nulls last, or keep documents that lack the sort field | sorts in memory, unless every match has the field |
| compare or sort decimals stored as strings by value | does both in memory; `DecimalStorage.DOUBLE` lets Firestore do them |
| group records (`Aggregating`) | doesn't offer it |
| answer a query without the composite index it needs | throws `MissingIndexException`, with Firestore's link to create it |

A query worked in memory may read at most `maxScan` documents (10,000 by default) and then throws
`UnsupportedQueryException`, an `InvalidQueryException` the API answers as `invalid_query`. With `maxScan = 0` every query
must be one Firestore answers on its own, so a slow query shows up at once instead of as data grows. A schema that sorts
by a field which may be unset can make it `required()`, or the client can filter on it. Firestore compares strings by
their UTF-8 bytes, which differs from BaseKt's order only for characters outside the Basic Multilingual Plane. The
emulator needs no indexes, so `MissingIndexException` only shows up against a real database.

**Transactions** are Firestore's, which lock the documents they read. A transaction's writes are kept in memory until
its block returns, so its reads see them (lists and counts included) and a failure leaves nothing behind, and a nested
transaction is a savepoint of that log. When Firestore aborts a transaction over a conflict, the block runs again, for
up to `transactionRetryTimeout` (30 seconds), and then throws `TransactionConflictException`.

**What may run twice.** A rerun starts the block from the top with an empty log, so what it writes through the source it
is given lands once. Everything else in the block runs once per attempt: hooks and actions run in the write's
transaction, so a hook or action that calls another service, sends a message or writes to a file store may do so more
than once. Put that work after the write, where a `WriteExtension`'s `committed` sees it once the transaction has
committed. BaseKt's server reports an action's writes to extensions only from the last run.

**Writes** outside a transaction run in one of their own, except a create, which is a single Firestore create.
`updateIf` reads the record, checks the condition and writes the increments in one transaction, so concurrent
"take one if any are left" updates never overshoot.

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
