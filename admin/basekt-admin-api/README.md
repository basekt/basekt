# basekt-admin-api

`install(BaseKtAdmin)`: a JSON admin API generated from the schema: a per-caller schema, lists with search, filters and sort, CRUD, bulk delete and CSV export, reports, and its TypeScript types.

```kotlin
dependencies { implementation("io.github.basekt:basekt-admin-api:0.2.0") }
```

Builds on: basekt-server.

## Use

```kotlin
install(BaseKt) { /* ... */ }
install(BaseKtAdmin)      // /admin/api

// in a resource: links from a record to your own pages
adminLinks { link("View on site", "/lessons/{id}") }
```

## Good to know

- Every operation goes through the same service as the REST API, so permissions, validation and hooks are the same.
- After changing its wire classes, regenerate the TypeScript types: `./gradlew :admin:basekt-admin-api:generateTypeScript`.

## Documentation

- [Admin API](https://basekt.github.io/basekt/admin-api/)
- [Admin panel and themes](https://basekt.github.io/basekt/admin-panel/)
- [All modules](../../README.md#modules)
