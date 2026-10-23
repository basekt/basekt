# BaseKt

**A modular backend and admin toolkit for Ktor.** You install only the pieces you need, as Ktor plugins:

- a schema-driven API;
- auth;
- data adapters;
- an admin panel;
- later, storage, realtime and a client SDK.

It's built for a shop, a SaaS, a game's back office or anything else that needs a backend and an admin, without
handing your app to a platform.

!!! note "Status"

    **0.1.0 is on Maven Central.** It has the schema, the data SPI with its in-memory and Exposed adapters and
    contract tests, the `install(BaseKt)` REST plugin with hooks, Ktor auth adapters, the JSON admin API, and the sample
    shop. The admin panel, `basekt-admin-ui`, works on `main` and ships in 0.2.0.

## Why another one

Supabase and Firebase are platforms you deploy *to*. Admin generators like KtorAdmin are one large plugin, driven by
annotation processing and tied to particular ORMs. BaseKt is a set of small libraries you deploy *with*:

- **Modular:** every capability is its own artifact and its own Ktor plugin. You skip the ones you don't use.
- **Runtime schema, no code generation required:** resources are described in a Kotlin DSL or as data. Generating
  them from data classes (KSP) is optional and comes later.
- **Adapters behind interfaces:** data, auth and storage each sit behind a small SPI. A new backend is a new module,
  not a fork.
- **Plain Ktor:** it works with `Application.module()`, `ApplicationConfig`, the app lifecycle, Ktor DI or Koin, and
  `testApplication`. No globals, and no engine owned by a library.

## What it looks like

```kotlin
fun Application.module() {
    install(Authentication) {
        baseKtJwt("jwt", JwtSettings.from(environment.config.config("auth.jwt")))   // basekt-auth
    }
    install(BaseKt) {
        dataSource = ExposedDataSource(database, schema)  // or InMemoryDataSource(), or any other adapter
        identities = AuthIdentities("jwt")                 // roles from the JWT, checked by the permissions
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
        hooks {
            beforeUpdate("products") { update ->
                if (update.changes["price"] == null) proceed(update.changes) else veto("price", "Prices are frozen.")
            }
        }
    }
    install(BaseKtAdmin) { path = "/admin/api" }           // basekt-admin-api
    install(BaseKtAdminUi) { path = "/admin" }             // basekt-admin-ui: the panel, from the jar
}
```

That serves a REST API for each resource (see [Schema and the REST API](schema.md)), the
[admin API](admin-api.md), and the [admin panel](admin-panel.md).

## Where to go next

- [Getting started](getting-started.md): install the modules and run the sample shop.
- [Schema](schema.md): resources, fields, relations, the REST API and typed models.
- [Modules](modules.md): every module and its status.
