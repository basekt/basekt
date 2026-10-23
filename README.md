# BaseKt

**A modular backend and admin toolkit for Ktor.** You install only the pieces you need, as Ktor plugins:
- a schema-driven API;
- auth;
- data adapters;
- an admin panel;
- later, storage, realtime and a client SDK.

It's built for a shop, a SaaS, a game's back office or anything else that needs a backend and an admin, without
handing your app to a platform.

> **Status: pre-release (0.1.0-SNAPSHOT).** These work now: the schema, the data SPI with its in-memory adapter and
> contract tests, the `install(BaseKt)` REST plugin, and the sample shop. Exposed, auth and the admin API come next.
> Nothing is published yet.

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
    install(BaseKt) {
        dataSource = InMemoryDataSource()             // or any other DataSource adapter
        identities = IdentityResolver { call -> /* map the call's principal to Identity(id, roles) */ null }
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
    }
}
```

That serves:

| Route | What it does |
|---|---|
| `GET /api/schema` | the schema as JSON. The same model can be written as JSON and read with `Schema.fromJson`. |
| `GET /api/{resource}` | a page of records: `items`, `nextCursor` and `hasNext`. Takes `field=value` and `field[op]=value` filters (`op` is eq, ne, gt, gte, lt, lte, in, contains or null), plus `sort=-price,name`, `limit` and `cursor`. |
| `POST /api/{resource}` | creates a record |
| `GET`, `PATCH`, `DELETE /api/{resource}/{id}` | reads, patches or deletes one record |
| `GET /api/{resource}/{id}/{relation}` | the record a `belongsTo` points to, or a page of a `hasMany` |

Errors are typed JSON: `{"error": "validation_failed", "message": ..., "fields": [{"field", "code", "message"}]}`.
The base path and page sizes come from the `basekt` section of `ApplicationConfig`. The runnable example is
[`samples/sample-shop`](samples/sample-shop): run `./gradlew :samples:sample-shop:run`, then try
`curl localhost:8080/api/products`.

## Modules

| Module | v0.1.0 | What it is |
|---|---|---|
| `core:basekt-schema` | ✓ works | resources, fields, relations, validation and permissions as data (multiplatform, no Ktor) |
| `server:basekt-server` | ✓ works | `install(BaseKt)`: the schema-driven REST API, permissions and lifecycle |
| `server:basekt-data` | ✓ works | the `DataSource` SPI: queries, writes, transactions |
| `server:basekt-data-memory` | ✓ works | in-memory adapter, for tests, samples and prototypes |
| `server:basekt-data-exposed` | next | Exposed/JDBC adapter: Postgres, MySQL, H2 |
| `server:basekt-auth` | next | Ktor Authentication adapters (JWT, bearer) and roles mapped to permissions |
| `admin:basekt-admin-api` | next | `install(BaseKtAdmin)`: admin endpoints from the schema (CRUD, actions, export) |
| `testing:basekt-testing` | ✓ works | the `DataSourceContract` suite every adapter runs, plus test fixtures |
| `samples:sample-shop` | ✓ works | a small shop backend (the admin comes with `basekt-admin-api`) |
| `admin:basekt-admin-ui` | later | a schema-driven admin panel (Compose Multiplatform for the web), served by the plugin |
| `server:basekt-graphql` | later | a GraphQL API generated at runtime from the same schema, on the same `DataSource` and service layer |
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
