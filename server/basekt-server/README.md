# basekt-server

`install(BaseKt)`: a REST API for every resource of a schema, with permissions and record scopes, validation, hooks, actions and workflows, and typed errors. It is a plain Ktor plugin.

```kotlin
dependencies { implementation("io.github.basekt:basekt-server:0.2.0") }
```

Builds on: basekt-data, basekt-schema.

## Use

```kotlin
fun Application.module() {
    install(BaseKt) {
        dataSource = ExposedDataSource(database, shopSchema)
        schema = shopSchema
        identities = AuthIdentities("jwt")
        hooks { beforeCreate("orders") { order -> proceed(order) } }
        actions { on("orders", "ship") { null } }
    }
}
```

## Good to know

- Routes are under `/api` by default: `GET /api/products`, `POST`, `PATCH`, `DELETE`, filters, sorting and cursor paging.
- Optional modules sit on it: `basekt-audit`, `basekt-files`, `basekt-admin-api`, `basekt-richtext`, `basekt-storefront`.
- A `WriteExtension` lets a module take part in every write, whichever route it came from.
- `identities = resolver.withServerRoles(ServerRoles.from(config), ServerRoles { identity, call -> ... })` adds roles from configuration or a lookup to the caller's own, once per call; see [Roles from the server](https://basekt.github.io/basekt/permissions/#roles-from-the-server).

## Documentation

- [Schema and the REST API](https://basekt.github.io/basekt/schema/)
- [Hooks](https://basekt.github.io/basekt/hooks/)
- [Actions and workflows](https://basekt.github.io/basekt/actions-workflows/)
- [Concurrent writes](https://basekt.github.io/basekt/concurrent-writes/)
- [All modules](../../README.md#modules)
