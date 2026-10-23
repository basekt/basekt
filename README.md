# BaseKt

**A modular backend and admin toolkit for Ktor.** You install only the pieces you need, as Ktor plugins:
- a schema-driven API;
- auth;
- data adapters;
- an admin panel;
- later, storage, realtime and a client SDK.

It's built for a shop, a SaaS, a game's back office or anything else that needs a backend and an admin, without
handing your app to a platform.

> **Status: pre-release (0.1.0-SNAPSHOT).** The module skeleton builds. The APIs below are the plan for v0.1.0, and
> nothing is published yet.

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

## A sketch of v0.1.0

```kotlin
fun Application.module() {
    install(BaseKt) {
        dataSource = ExposedDataSource(database)      // or InMemoryDataSource()
        resources {
            resource<Product>("products") {
                field(Product::price) { min(0) }
                permissions { read(everyone); write("staff") }
            }
            resource<Order>("orders") {
                belongsTo(Order::customer, "customers")
                permissions { read("staff"); write("admin") }
            }
        }
    }
    install(BaseKtAuth) { jwt(issuer = config.issuer, audience = "shop") }
    install(BaseKtAdmin) { path = "/admin" }
}
```

## Modules

| Module | v0.1.0 | What it is |
|---|---|---|
| `core:basekt-schema` | ✓ | resources, fields, relations, validation and permissions as data (multiplatform, no Ktor) |
| `server:basekt-server` | ✓ | `install(BaseKt)`: the resource registry, the schema-driven REST API and lifecycle |
| `server:basekt-data` | ✓ | the `DataSource` SPI: queries, writes, transactions |
| `server:basekt-data-memory` | ✓ | in-memory adapter, for tests, samples and prototypes |
| `server:basekt-data-exposed` | ✓ | Exposed/JDBC adapter: Postgres, MySQL, H2 |
| `server:basekt-auth` | ✓ | Ktor Authentication adapters (JWT, bearer) and roles mapped to permissions |
| `admin:basekt-admin-api` | ✓ | `install(BaseKtAdmin)`: admin endpoints from the schema (CRUD, actions, export) |
| `testing:basekt-testing` | ✓ | `testApplication` fixtures and in-memory everything |
| `samples:sample-shop` | ✓ | a small shop backend with an admin |
| `admin:basekt-admin-ui` | later | a schema-driven admin panel (Compose Multiplatform for the web), served by the plugin |
| `server:basekt-realtime` | later | change feeds over WebSockets |
| `server:basekt-storage` (+ `-local`, `-s3`, `-gcs`) | later | file uploads behind one interface |
| `server:basekt-audit` | later | an audit log of who changed what |
| `client:basekt-client` | later | a typed multiplatform client SDK: auth, queries, realtime, storage |
| `server:basekt-data-jdbc`, `-mongo` | later | more data adapters |
| `server:basekt-auth-oidc`, `-firebase` | later | more auth providers |

## Building

JDK 17 or later.

```bash
./gradlew build
```

Maven coordinates, once published: `io.github.basekt:<module>:<version>`.

## Contributing

Read [AGENTS.md](AGENTS.md) for the design rules every module follows, and copy its checklist into each PR.

## License

[Apache-2.0](LICENSE).
