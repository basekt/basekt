# BaseKt

**A modular backend and admin toolkit for Ktor.** You install only the pieces you need, as Ktor plugins:
- a schema-driven API;
- auth;
- data adapters;
- an admin panel;
- later, storage, realtime and a client SDK.

It's built for a shop, a SaaS, a game's back office or anything else that needs a backend and an admin, without
handing your app to a platform.

> **Status: pre-release (0.1.0-SNAPSHOT).** Every v0.1.0 module works: the schema, the data SPI with its in-memory
> and Exposed adapters and contract tests, the `install(BaseKt)` REST plugin with hooks, Ktor auth adapters, the JSON
> admin API, and the sample shop. Nothing is published yet.

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
A caller without credentials gets `401 unauthorized`, one without the role `403 forbidden`. The base path and page
sizes come from the `basekt` section of `ApplicationConfig`.

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

**Hooks** run in the service layer, so the REST API, the admin API and any later transport run the same ones. A
before-hook on create, update or delete can modify the input or veto the write with a reason. An after-hook runs in
the same transaction.

**The admin API** is JSON only, for a panel to render itself from:

| Route under `/admin/api` | What it does |
|---|---|
| `GET /schema` | what the caller may see and do: `"version": 1`, the resources, fields, relations and allowed operations |
| `GET /resources/{name}/records` | a page with a `total`: the same filters, sort and cursor, plus `search` |
| `POST`, `GET`, `PATCH`, `DELETE /resources/{name}/records[/{id}]` | create, read, update and delete |
| `POST /resources/{name}/bulk-delete` | deletes several records and reports each one |
| `GET /resources/{name}/export.csv` | the list as CSV |

Its wire format has TypeScript types in
[`admin/basekt-admin-api/typescript`](admin/basekt-admin-api/typescript), generated from the Kotlin classes and
checked in CI. The schema contract is additive only within a version.

The runnable example is [`samples/sample-shop`](samples/sample-shop): Exposed on H2, JWT and demo bearer tokens, a
"price can't drop below cost" hook and the admin API. Run `./gradlew :samples:sample-shop:run`, then try
`curl localhost:8080/api/products` or `curl -H "Authorization: Bearer staff-token" localhost:8080/admin/api/schema`.

## Modules

| Module | v0.1.0 | What it is |
|---|---|---|
| `core:basekt-schema` | ✓ works | resources, fields, relations, validation and permissions as data (multiplatform, no Ktor) |
| `server:basekt-server` | ✓ works | `install(BaseKt)`: the schema-driven REST API, permissions, hooks and lifecycle |
| `server:basekt-data` | ✓ works | the `DataSource` SPI: queries, writes, transactions, and optional capabilities such as `Counting` |
| `server:basekt-data-memory` | ✓ works | in-memory adapter, for tests, samples and prototypes |
| `server:basekt-data-exposed` | ✓ works | Exposed/JDBC adapter: Postgres, MySQL, H2. Keyset cursors, one-query `getMany`, savepoints |
| `server:basekt-auth` | ✓ works | Ktor Authentication adapters (JWT, bearer), `PrincipalRoles` mapped to permissions, typed 401 and 403 |
| `admin:basekt-admin-api` | ✓ works | `install(BaseKtAdmin)`: a JSON admin API from the schema (per-caller schema, list and search, CRUD, bulk delete, CSV export) and its TypeScript types |
| `testing:basekt-testing` | ✓ works | the `DataSourceContract` suite every adapter runs, plus test fixtures |
| `samples:sample-shop` | ✓ works | a small shop backend: Exposed on H2, JWT and bearer auth, a pricing hook and the admin API |
| `admin:basekt-admin-ui` | later | a schema-driven admin panel (React, shadcn and Tailwind, prebuilt into the jar and themed with CSS variables), served by the plugin from the admin API |
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

The Exposed adapter's tests run on H2. To also run its contract suite on Postgres, pass a database it may create
tables in:

```bash
./gradlew :server:basekt-data-exposed:test -Pbasekt.postgres.url=jdbc:postgresql://localhost/basekt -Pbasekt.postgres.user=basekt -Pbasekt.postgres.password=basekt
```

After changing the admin API's wire classes, regenerate the TypeScript types and commit them:

```bash
./gradlew :admin:basekt-admin-api:generateTypeScript
```

Maven coordinates, once published: `io.github.basekt:<module>:<version>`.

## Contributing

Read [AGENTS.md](AGENTS.md) for the design rules every module follows, and copy its checklist into each PR.

## License

[Apache-2.0](LICENSE).
