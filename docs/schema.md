# Schema and the REST API

Resources, their fields and their relations are described in a Kotlin DSL inside `install(BaseKt)`, as in the
[home page's example](index.md#what-it-looks-like):

```kotlin
resources {
    resource("products") {
        string("name") { required(); length(max = 120) }
        decimal("price") { required(); min("0.01") }
        belongsTo("category", "categories")
        permissions { read(everyone); write("staff") }
    }
    resource("categories") {
        string("name") { required() }
        hasMany("products", "products", foreignField = "categoryId")
        permissions { read(everyone); write("staff") }
    }
}
```

The schema is data at runtime: no annotation processing or code generation is required.

## Routes

The schema serves:

| Route | What it does |
|---|---|
| `GET /api/schema` | the schema as JSON. The same model can be written as JSON and read with `Schema.fromJson`. |
| `GET /api/{resource}` | a page of records: `items`, `nextCursor` and `hasNext`. Takes `field=value` and `field[op]=value` filters (`op` is eq, ne, gt, gte, lt, lte, in, contains or null), plus `sort=-price,name`, `limit` and `cursor`. |
| `POST /api/{resource}` | creates a record |
| `GET`, `PATCH`, `DELETE /api/{resource}/{id}` | reads, patches or deletes one record. A delete follows the [delete rules](#deleting-records) of the records that belong to it |
| `GET /api/{resource}/{id}/{relation}` | the record a `belongsTo` points to, or a page of a `hasMany` |

Errors are typed JSON: `{"error": "validation_failed", "message": ..., "fields": [{"field", "code", "message"}], "requestId": ...}`.
A caller without credentials gets `401 unauthorized`, one without the role `403 forbidden`, and one whose [server roles](permissions.md#roles-from-the-server) couldn't be looked up
`503 unavailable`. The base path and page
sizes come from the `basekt` section of `ApplicationConfig`.

### Request ids and server errors

Every call of the application gets a **request id**, sent back in the `X-Request-Id` header and in every error body.
An id a proxy sends in that header is kept when it is a plain token. An unexpected exception is answered as
`500 internal_error`, whose message gives only the request id, and logged through the application's log with the id,
the method and the path (never the body or credentials), so a client's report finds the log line. Rename the header
with `basekt.requestIdHeader`; a browser on another origin reads it only if your CORS settings expose it. Other modules
answer their errors the same way with `call.answeringErrors { }` and `call.respondError(...)`.

## Record ids

Every record has a string `id`, and it never changes. A resource says where ids come from:

| Declaration | Where the id comes from |
|---|---|
| nothing (`"source": "generated"`) | the data source makes one, unless the create sends one |
| `naturalId { ... }` (`"natural"`) | the client sends one on every create, such as a username, a slug or a reverse-DNS name |
| `keyedBy("a", "b")` (`"key"`) | the server makes it from the values of two or more fields |

Every id a create sends, whatever the source, is 1 to 256 characters long, holds no `/` or control characters, isn't `.`
or `..`, and doesn't both start and end with `__`, so each adapter can store it and a route can name it. A data source
may store fewer characters: the Exposed adapter's `TableMapping.idLength` is 64 by default.

### Natural ids

```kotlin
resource("handles") {
    naturalId { length(min = 3, max = 20); pattern("[a-z0-9_]+", "Use lowercase letters, digits and '_'.") }
    string("display")
}
resource("packages") {
    naturalId { pattern("[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+") }   // com.example.tools
}
```

A create without an id is refused with `422 validation_failed` and a `required` error on `id`, and one that breaks a
rule with that rule's error on `id` (`length`, `pattern` or `custom`). An id another record has is refused with
`409 conflict`; the data source decides it atomically, so of two callers creating the same id at once exactly one wins.

**Renames aren't supported.** A `PATCH` that sends `id` is refused with a `read_only` error, and an old id never
resolves to a new record. To give a record a new id, write an [action](actions-workflows.md) that creates the record
under the new id, points the records that reference the old one at it, and deletes the old one, all in its one
transaction. Keep a name that may change, such as a display name, in a field of its own.

### Keys of several fields

A record unique per two or more fields together, such as one grant per account and item, makes its id from them:

```kotlin
resource("grants") {
    belongsTo("account", "accounts", onDelete = OnDelete.CASCADE) { required() }
    belongsTo("item", "items", onDelete = OnDelete.CASCADE) { required() }
    string("level")
    keyedBy("accountId", "itemId")
}
```

The id is the fields' values joined with `~`, after `%`, `~`, `/` and control characters in each value are
percent-encoded: account `alice` and item `item-7` make `alice~item-7`, and account `row~4` and season `2026` make
`row%7E4~2026`. So the REST routes address the record by both parts: `GET /api/grants/alice~item-7`. In a URL path the
id's own `%` is sent as `%25`, as any client's URL encoding does. `IdSpec.keyOf(parts)` and `IdSpec.partsOf(id)` build
and split ids on the server; `GET /api/grants?accountId=alice` finds every record with one part.

Key fields are string, enum, reference, int or long fields, and a key may use `createdBy()` or an inherited field,
which are set once on create. The create must give every key field a value (or a `required` error names the one
missing), it can't send an id itself (`read_only`), and an update can't change a key field (`read_only`). Because the
key is the record's id, the data source refuses a second record with the same values atomically, in every adapter:
concurrent creates of one key give one `201` and `409 conflict` for the rest, with the message `There is already a
'grants' with this accountId and itemId.` A `unique()` field, by contrast, is checked in the write's transaction and
backed by a unique index in the Exposed and MongoDB adapters but not in Firestore, which has none, and it covers one
field only. So a key is the way to make a pair unique under concurrent writes, in every adapter.

## Deleting records

When a record is deleted, each `belongsTo` that points to it says what happens to the record that holds it:

```kotlin
resource("posts") {
    belongsTo("thread", "threads", onDelete = OnDelete.CASCADE) { required() }   // deleted with its thread
    belongsTo("editor", "members", onDelete = OnDelete.SET_NULL)                  // editorId cleared
    belongsTo("category", "categories")                                            // RESTRICT, the default
}
```

| `onDelete` | When the target is deleted |
|---|---|
| `RESTRICT` (default) | the delete is refused with `409 conflict` while any record points to it, naming how many and one of them |
| `CASCADE` | the record is deleted too, and its own referrers' rules apply in turn, all the way down |
| `SET_NULL` | the reference field is set to null. The field can't be required or part of a key |

All of it runs in the delete's one transaction. The delete first finds every record it reaches and checks every
restriction, so a record the same delete removes doesn't hold it back; then it clears the `SET_NULL` references, as
updates with their hooks, versions and audit entries; then it deletes each record, the one asked for first, each with
its before- and after-delete [hooks](hooks.md) and its own [audit](audit.md) entry, all under one operation id. A veto
from any hook, or any failure, rolls the whole delete back. The caller's permission to delete the first record covers
the rest: cascades don't check their permissions or scopes, since the schema declared them.

Only `belongsTo` relations take part. A plain `reference` field without one is left as it is when its target goes.

### Erasing what a principal owns

When everything a principal owns belongs, through cascading relations, to one record such as their account, deleting
that record erases it all in one transaction. For records that name their owner another way, such as a `createdBy()`
field, an [action](actions-workflows.md) calls `deleteWhere` for each resource, which deletes every matching record with
the same steps as `DELETE`, cascades included:

```kotlin
resource("accounts") {
    naturalId()
    action("erase") { allow("admin") }
}

actions {
    on("accounts", "erase") {
        val id = record!![Resource.ID] as Value.Text
        val owned = Filter.Compare("createdBy", Comparison.EQ, id)
        val removed = deleteWhere(posts, owned) + deleteWhere(uploads, owned)   // the schema's Resources
        delete(resource, id.value)                                               // and whatever cascades from it
        buildJsonObject { put("removed", removed) }
    }
}
```

The action's writes are one transaction, so a refusal anywhere leaves everything in place, and every record removed
runs its hooks and gets its audit entry.

## Live data from your own code

Some records an admin needs aren't stored: what a queue holds right now, the readings of a device, or records another
service owns behind its HTTP API. Declare such a resource `provided()`, and register a `RecordProvider` that serves
its records:

```kotlin
resources {
    resource("docks") {
        provided()
        string("station") { required() }
        string("zone") { required() }
        int("bikes")
        action("release") { allow(authenticated) }
        permissions { read(authenticated); scope("zone", attribute = "zone", unless = setOf("operator")) }
    }
}
providers {
    provide("docks", object : RecordProvider {
        override val filters = mapOf("zone" to setOf(FilterOperator.IN), "bikes" to setOf(FilterOperator.GTE))
        override val sorts = setOf("bikes")
        override suspend fun list(query: Query): Page = controller.docks(query)   // at most query.limit, after query.cursor
        override suspend fun get(id: String): Record? = controller.dock(id)
    })
}
```

- **The provider says which filters and sorts it supports.** A list that uses any other, such as `bikes[gt]=3` above,
  is refused with `400 invalid_query` before the provider is called, naming what it takes. The filter it gets is
  made of those comparisons, joined by `And` and, for a search or a scope with several ways in, `Or`. Without a sort,
  records come in id order, and a cursor is whatever the provider puts in `Page.nextCursor`.
- **It is read-only everywhere.** Every create, update and delete, through the REST API, the admin API and an
  action's `create`, `update` and `delete`, is refused with `405 read_only`. The schema refuses what only a write
  would use: create, update or delete permissions, a workflow, automatic, unique and file fields, reports, ids that
  aren't generated, and a reference field anywhere that points to it. Aggregates answer `501 not_implemented`.
- **Permissions, scopes and actions apply as for any resource.** The caller's read scope reaches the provider as an
  `In` filter on each scoped field, so a provider of a scoped resource must support `in` on it; a caller with no
  values reaches no record without the provider being asked, and the service checks every returned record against
  the scope again. A record action loads its record from the provider, may write stored resources and call whatever
  the record describes, and answers with the record as the provider has it afterwards.
- **A provider that fails answers `503 unavailable`** with the [request id](#request-ids-and-server-errors), and the
  cause goes to the log only. Records that don't fit the resource (a missing text id, an unknown field, a value of
  the wrong type, or more than the limit) count as a failure. A provider may throw an `ApiException` to answer
  something else, and an `InvalidQueryException` for a cursor it didn't issue.

`install(BaseKt)` refuses a provided resource without a provider, a provider of a resource that isn't provided,
filters or sorts on fields the API doesn't serve, a scoped field without `in`, and hooks on a provided resource, listing
every problem at once. The [admin panel](admin-panel.md#live-read-only-resources) lists a provided resource with no edit
controls.

## Typed models

**Typed models** are an optional layer on the same runtime schema. A `@Serializable` class describes a resource, and
typed and dynamic resources mix in one schema:

```kotlin
@Serializable
data class Product(val id: String? = null, val name: String, val price: Value.Decimal, val categoryId: String? = null)

resources {
    resource("categories") { string("name") { required() } }       // dynamic
    resource<Product>("products") {                                  // typed: fields come from the serializer
        field(Product::price) { min("0.01") }
        belongsTo(Product::categoryId, "categories")
    }
}
hooks { beforeUpdate { old: Product, new: Product -> if (new.price < old.price) veto("price", "No discounts.") else proceed(new) } }

val cheap = application.baseKt.repository<Product>().list {
    where { Product::price lt Value.Decimal.of("10") }               // compiles only with a value of the property's type
    orderBy(Product::price)
}
```

The class's fields come from its serializer, with no reflection and no code generation. A typed resource is the same
`Resource` a dynamic one is, so validation, permissions, the REST API and the admin API don't change. A `Repository`
is data access for trusted server code: it doesn't check permissions or run hooks.

## Related

- [Permissions and record scopes](permissions.md)
- [Automatic fields](automatic-fields.md) and [concurrent writes](concurrent-writes.md)
- [File fields](files.md#file-fields)
- [OpenAPI](openapi.md): this API as an OpenAPI 3.1 document, for typed clients
