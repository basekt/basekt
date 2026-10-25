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
| `GET`, `PATCH`, `DELETE /api/{resource}/{id}` | reads, patches or deletes one record |
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
