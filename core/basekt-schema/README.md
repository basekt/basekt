# basekt-schema

Resources, typed fields, relations, validation and permissions as data. A schema has a Kotlin DSL, a JSON form and typed models, and is checked when it is built. It is a multiplatform module with no Ktor and no database, so a client can read the same schema a server serves.

```kotlin
dependencies { implementation("io.github.basekt:basekt-schema:0.2.0") }
```

## Use

```kotlin
val shop = schema {
    resource("products") {
        string("name") { required(); length(max = 120) }
        decimal("price") { required(); min("0") }
        belongsTo("category", "categories")
        permissions { read(everyone); write("staff") }
    }
    resource("categories") { string("name") { required() } }
}
```

## Good to know

- Everything else in BaseKt reads a `Schema`: the server, the admin, the data adapters.
- Optional modules add their own blocks to a resource (`storefront { }`, `adminLinks { }`) through `Extensible`, without changing the schema.
- A field can be `unique()`, `hidden()`, `markdown { }`, a `file(...)`, or set by the server (`createdAt()`, `sequence()`, `version()`).

## Documentation

- [Schema and the REST API](https://basekt.github.io/basekt/schema/)
- [Permissions](https://basekt.github.io/basekt/permissions/)
- [Automatic fields](https://basekt.github.io/basekt/automatic-fields/)
- [Extending BaseKt](https://basekt.github.io/basekt/extending/)
- [All modules](../../README.md#modules)
