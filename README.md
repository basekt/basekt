# BaseKt

**A modular backend and admin toolkit for Ktor.** You install only the pieces you need, as Ktor plugins:
- a schema-driven API;
- auth;
- data adapters;
- an admin panel;
- later, storage, realtime and a client SDK.

It's built for a shop, a SaaS, a game's back office or anything else that needs a backend and an admin, without
handing your app to a platform.

> **Status: 0.1.0 is on Maven Central.** It has the schema, the data SPI with its in-memory and Exposed adapters and
> contract tests, the `install(BaseKt)` REST plugin with hooks, Ktor auth adapters, the JSON admin API, and the sample
> shop. The admin panel, `basekt-admin-ui`, works on `main` and ships in 0.2.0.

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

That serves:

| Route | What it does |
|---|---|
| `GET /api/schema` | the schema as JSON. The same model can be written as JSON and read with `Schema.fromJson`. |
| `GET /api/{resource}` | a page of records: `items`, `nextCursor` and `hasNext`. Takes `field=value` and `field[op]=value` filters (`op` is eq, ne, gt, gte, lt, lte, in, contains or null), plus `sort=-price,name`, `limit` and `cursor`. |
| `POST /api/{resource}` | creates a record |
| `GET`, `PATCH`, `DELETE /api/{resource}/{id}` | reads, patches or deletes one record |
| `GET /api/{resource}/{id}/{relation}` | the record a `belongsTo` points to, or a page of a `hasMany` |

Errors are typed JSON: `{"error": "validation_failed", "message": ..., "fields": [{"field", "code", "message"}], "requestId": ...}`.

Every call of the application gets a **request id**, sent back in the `X-Request-Id` header and in every error body.
An id a proxy sends in that header is kept when it is a plain token. An unexpected exception is answered as
`500 internal_error`, whose message gives only the request id, and logged through the application's log with the id,
the method and the path (never the body or credentials), so a client's report finds the log line. Rename the header
with `basekt.requestIdHeader`; a browser on another origin reads it only if your CORS settings expose it. Other modules
answer their errors the same way with `call.answeringErrors { }` and `call.respondError(...)`.
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

**Record scopes** narrow permissions down to records. A caller's `Identity` carries attributes besides its roles,
such as the teams or sites they belong to, and a scope says which field of a record must hold one of those values:

```kotlin
resource("appointments") {
    reference("clinicId", "clinics") { required() }
    permissions {
        read("staff"); write("staff")
        scope("clinicId", attribute = "clinic", unless = setOf("director"))   // only your clinics' records
    }
}
resource("entries") {
    string("authorId")
    permissions { read(authenticated); write(authenticated); ownedBy("authorId", on = writes) }  // edit only your own
}
```

Lists, counts and relation routes hold only the records in the caller's scope, and a record outside it is `404`. A
record you may read but not change is `403`, and so is a write that would give a scoped field a value outside the
caller's. A create that leaves the field out gets the caller's value when they have exactly one. With JWTs,
`PrincipalRoles.jwt(attributeClaims = listOf("clinic"))` reads attributes from claims, and `JwtSettings.issue` writes
them. Scopes are schema data like everything else. Hooks and repositories are trusted server code, so scopes don't
apply to them.

**Automatic fields** are set by the server, never the client: when and by whom a record was created and last
changed, and readable numbers for documents.

```kotlin
resource("requests") {
    string("ref") { sequence("REQ-", digits = 6) }   // REQ-000001, REQ-000002...
    timestamps()                                      // createdAt and updatedAt
    string("createdBy") { createdBy() }               // the caller's id; also updatedBy()
    permissions { read(authenticated); write(authenticated); ownedBy("createdBy", on = writes) }
}
```

They're filled in the write's transaction before the before-hooks, so hooks see them. A client that sends one gets
`read_only`, and a `null` is ignored. Numbers come from the data source's `Sequencing` capability: they never repeat,
and a write that fails gives its number back, so there are no gaps. The in-memory and Exposed adapters offer it. The
Exposed adapter keeps counters in a `basekt_sequences` table. Set `clock` in `install(BaseKt)` to control the times in
tests.

**Concurrent writes** are safe in two ways. A `version()` field counts a record's writes. An update that sends the
version it read is refused with `409 conflict` if someone else saved in between, instead of silently overwriting
their change. The admin panel sends it on every edit.

```kotlin
resource("pages") {
    string("body")
    long("version") { version() }   // 1 on create, +1 on every update; PATCH {"body": "...", "version": 3}
}
```

For numbers many writers change at once, such as what's left of something, the data source's `ConditionalWrites`
capability updates a record only if it still matches a condition, and adds to number fields, in one atomic step. A
hook takes one only if any are left, and concurrent writers can't take more than there is:

```kotlin
when ((data as ConditionalWrites).updateIf(pools, poolId, Filter.Compare("left", Comparison.GTE, Value.Integer(1)),
    increments = mapOf("left" to Value.Integer(-1)))) {
    is ConditionalUpdate.Updated -> proceed(claim)
    else -> veto("poolId", "None left.")
}
```

The in-memory and Exposed adapters offer it. The Exposed adapter does it as one `UPDATE … WHERE`, which the database
checks again under the row's lock.

**Actions** are named operations beyond create, update and delete, such as lending a copy or archiving everything
that's done. The declaration is schema data, with an input checked like a record, who may run it, and whether it runs
on one record or the whole resource. The code is registered at install:

```kotlin
resource("copies") {
    int("available") { required(); min(0) }
    action("lend") {
        input { string("borrower") { required() } }
        allow("librarian")
        confirm("Lend a copy?")
    }
}

install(BaseKt) {
    actions {
        on("copies", "lend") { input ->
            val copy = (record!!["id"] as Value.Text).value
            val anyLeft = Filter.Compare("available", Comparison.GTE, Value.Integer(1))
            if ((data as ConditionalWrites).updateIf(copies, copy, anyLeft, increments = mapOf("available" to Value.Integer(-1)))
                !is ConditionalUpdate.Updated) fail("None left.")
            create(loans, mapOf("copyId" to Value.Text(copy), "borrower" to input["borrower"]))
            null   // answer with the record as it is now
        }
    }
}
```

The action is served at `POST /api/copies/{id}/actions/lend`, or `POST /api/{resource}/actions/{name}` for a
resource action. Everything it writes is one transaction, and `fail` rolls it all back. `create`, `update` and
`delete` in an action go through the same steps as the REST API (automatic fields, validation, hooks, versions), with
the action's permission in place of the caller's. A record action needs the record in the caller's update scope. The
admin API lists the actions each caller may run, and the panel shows them as buttons, with a form for their input.

**Audit** (`basekt-audit`) logs every create, update, delete and action, from every transport, as entries of a
resource in your own schema:

```kotlin
resources {
    auditLog("audit", readers = setOf("admin"))   // read-only through every API
    // ...
}
install(BaseKt) { /* ... */ }
install(BaseKtAudit) { redact("users.pin"); skip("sessions") }
```

Each entry has when it happened, the actor, the operation, the resource and record, and what changed: a create's
fields, an update's `from` and `to` for each changed field, or what a delete removed. An action's entry holds its
input, and shares an `operationId` with the writes it made. Entries are written in the same transaction as the write,
so a write that rolls back leaves no entry. Because the log is an ordinary resource, `GET /api/audit?recordId=p1`
gives one record's history, and the admin panel lists it.

**Aggregates** compute grouped totals in the data source instead of loading every record: stock on hand from a
ledger of movements, totals per site, or a dashboard's numbers.

```
GET /api/aggregate/movements?groupBy=itemId,siteId&sum=quantity&count&max=at&quantity[gt]=0&sort=-sum_quantity&limit=50
→ {"groups": [{"key": {"itemId": "i1", "siteId": "north"}, "values": {"sum_quantity": 7, "count": 2, "max_at": "…"}}],
   "hasMore": false}
```

The parameters are:
- `groupBy`, with one or more fields. An instant field can be grouped by `day`, `week` (from Monday), `month` or
  `year`, as in `groupBy=at:month`, and its key is the start of each span. Spans follow UTC's calendar, or a time zone
  region's with `timeZone=Asia/Manila`;
- `count` (records, or `count=*`), `count=field` (records with the field set), and `sum=`, `avg=`, `min=` and `max=`,
  each with one or more fields;
- `sort`, by group fields or measure names, and `limit`, up to `basekt.aggregate.maxLimit` (1000 by default);
- the usual filters.

Sums keep integers as integers. An average is exact to 12 places on every database. The caller's read scope applies,
so staff see only their own sites' totals. It needs a data source that offers `Aggregating`, as the in-memory and
Exposed adapters do (Exposed runs one `SELECT … GROUP BY`). Otherwise it's `501 not_implemented`. The Exposed adapter
groups by time spans on H2 and Postgres; on other databases a `groupBy` with one is refused as `invalid_query`. On H2,
a span in a zone with daylight saving splits on the day the clocks change. The admin API serves the same at
`GET /admin/api/resources/{name}/aggregate`, with `search` too.

**Reports** are charts of those aggregates, declared on a resource as schema data. The admin panel draws them on its
home screen:

```kotlin
resource("orders") {
    enum("status", listOf("placed", "paid", "shipped"))
    decimal("total")
    instant("placedAt")
    report("orderCount", Chart.NUMBER)                                  // a count of every order
    report("revenueByStatus") { groupBy("status"); sum("total") }        // a bar per status, largest first
    report("ordersByStatus", Chart.PIE) { groupBy("status"); limit(5) }
    report("revenuePerDay", Chart.LINE) {                               // a time series: the last 30 days
        groupBy("placedAt", TimeBucket.DAY, timeZone = "Asia/Manila")   // days of Manila's calendar
        sum("total")
        where("status", ReportOperator.NE, "cancelled")
        limit(30)
    }
}
```

A report is a `number`, `bar`, `line` or `pie`. It counts records, or takes the `sum`, `avg`, `min` or `max` of a
number field, for each value of its group field. Bars and pies show the largest groups first, and lines go in group
order. `order(...)` changes that, and `limit(...)` caps the groups. A line grouped by a time span is a time series: it
shows the latest spans and leaves out records without a time. Spans follow UTC's calendar unless the report names a
time zone region. `where(...)` counts only the records that match, with the operators of the list filters (`eq`, `ne`,
`gt`, `gte`, `lt`, `lte`, `in`, `contains` and `null`). A report gives no new access: the caller needs read
access to the resource, its scope applies to the numbers, and the panel fetches them from the aggregate route. The
panel draws them with shadcn/ui's charts, in the theme's `chart-1` to `chart-5` colours.

**Workflows** make an enum field a state machine, for approvals and anything else that moves through statuses. The
server sets the state: records start in the initial one, clients can't write it, and only named transitions change it:

```kotlin
resource("claims") {
    decimal("amount") { required() }
    string("owner") { createdBy() }
    enum("status", listOf("draft", "pending", "approved", "rejected"))
    workflow("status", initial = "draft") {
        transition("submit", from = "draft", to = "pending") { allow(authenticated) }
        transition("approve", from = "pending", to = "approved") { allow("manager"); input { string("note") } }
        transition("reject", from = "pending", to = "rejected") { allow("manager") }
    }
}

install(BaseKt) {
    actions {   // optional: a transition's guard and side effects, in its transaction
        on("claims", "approve") { if ((record!!["owner"] as Value.Text?)?.value == caller?.id) fail("Not your own claim."); null }
    }
}
```

A transition runs like a record action, at `POST /api/claims/{id}/actions/approve`. It checks the role and the
caller's scope, then the record's state (`409` if the transition can't start from it), then runs the code, if any. The
state then changes through the usual write steps (automatic fields, hooks, versions, audit), and only if no other
transition moved the record in the meantime. The admin panel shows each record's valid transitions as buttons.

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

**The admin panel** (`basekt-admin-ui`) is a React, shadcn/ui and Tailwind app prebuilt into the jar, so the host
needs no Node. It renders every screen from the admin API's `/schema`, so adding a resource needs no panel change:

- a sidebar of what the caller may see, and a home screen with the reports they may read, as charts;
- lists with search, filters, sort, cursor paging, bulk delete and CSV export;
- an error card that says when the server couldn't be reached, shows the request id, and copies a report or
  downloads the page's recent failed calls (kept in memory only) for whoever runs the panel;
- forms with a widget per field type, including a searchable picker for references, and validation errors and hook
  vetoes shown on their fields.

It hides what the caller's roles don't allow, signs in with a bearer token or rides the host's cookie session, and
has light and dark modes. Its theme is typed Kotlin that becomes CSS variables at runtime, so changing it needs no
rebuild:

```kotlin
install(BaseKtAdminUi) {
    brand { name = "Shop"; logo = "/static/logo.svg" }
    theme {
        use(Theme.fromCss(File("theme.css").readText()))   // optional: a theme from ui.shadcn.com/themes
        radius = Radius.rem(0.5)
        light { this[Token.Primary] = Oklch(0.511, 0.262, 276.966) }
        dark { this[Token.Primary] = Hex("#818cf8") }
    }
}
```

![The sample shop's products in the admin panel](admin/basekt-admin-ui/docs/staff-products.jpg)

**Files** (`basekt-files`) are kept in buckets over any `BlobStore`: in memory, on local disk, or on S3 with
`S3BlobStore(S3Settings(endpoint, bucket, region, accessKey, secretKey))` from `basekt-storage-s3`.

```kotlin
install(BaseKtFiles) {
    store = LocalBlobStore(Path.of("uploads"))                     // basekt-storage-local
    bucket("images") {
        maxBytes = 5 * MEGABYTE
        accept("image/png", "image/jpeg", "image/webp")
        read(everyone)
        write("staff")
    }
}
```

`POST /files/images` takes a `multipart/form-data` upload and answers `{key, bucket, name, size, contentType, url}`.
`GET /files/{key}` serves it with byte ranges and ETags. An upload's type comes from its bytes, never from what the
client claims, and it is stopped as soon as it passes the bucket's limit. Downloads send `nosniff` and a sandboxing
CSP, and only images, audio, video, PDF and plain text are shown inline. HTML, SVG and everything else download as
attachments.

A **file field** points a record at an uploaded file: `file("image", "images")`, or `file(Product::image, "images")`
on a typed model. Its value is the file's key. A write may only point at a file that exists in that bucket. When a
record stops pointing to a file and no other record points to it, the file is deleted after the write commits. Uploads
that no record ever points to are swept after a day. The admin panel uploads, previews and replaces files in these
fields.

The runnable example is [`samples/sample-shop`](samples/sample-shop): Exposed on H2, JWT and demo bearer tokens, a
"price can't drop below cost" hook, product images on local disk, the admin API, and the admin panel with an indigo
theme set in Kotlin. Run
`./gradlew :samples:sample-shop:run`, then try `curl localhost:8080/api/products`, or open
`http://localhost:8080/admin` and sign in with `staff-token` (everything) or `customer-token` (browse, place orders).

## Modules

| Module | Status | What it is |
|---|---|---|
| `core:basekt-schema` | ✓ works | resources, fields, relations, validation and permissions as data (multiplatform, no Ktor) |
| `server:basekt-server` | ✓ works | `install(BaseKt)`: the schema-driven REST API, permissions, hooks and lifecycle |
| `server:basekt-data` | ✓ works | the `DataSource` SPI: queries, writes, transactions, and optional capabilities such as `Counting` |
| `server:basekt-data-memory` | ✓ works | in-memory adapter, for tests, samples and prototypes |
| `server:basekt-data-exposed` | ✓ works | Exposed/JDBC adapter: Postgres, MySQL, H2. Keyset cursors, one-query `getMany`, savepoints |
| `server:basekt-auth` | ✓ works | Ktor Authentication adapters (JWT, bearer), `PrincipalRoles` mapped to permissions, typed 401 and 403 |
| `admin:basekt-admin-api` | ✓ works | `install(BaseKtAdmin)`: a JSON admin API from the schema (per-caller schema, list and search, CRUD, bulk delete, CSV export) and its TypeScript types |
| `testing:basekt-testing` | ✓ works | the `DataSourceContract` and `BlobStoreContract` suites every adapter runs, plus test fixtures |
| `admin:basekt-admin-ui` | ✓ works | `install(BaseKtAdminUi)`: a schema-driven admin panel (React, shadcn/ui and Tailwind, prebuilt into the jar) with a typed Kotlin theme |
| `samples:sample-shop` | ✓ works | a small shop backend: Exposed on H2, JWT and bearer auth, a pricing hook, the admin API and the admin panel |
| `server:basekt-graphql` | later | a GraphQL API generated at runtime from the same schema, on the same `DataSource` and service layer |
| `server:basekt-realtime` | later | change feeds over WebSockets |
| `server:basekt-storage` | ✓ works | the `BlobStore` SPI: streamed puts and reads of files, byte ranges, and optional listing and presigned URLs |
| `server:basekt-storage-memory`, `-local` | ✓ works | in-memory and local-filesystem stores, both passing the `BlobStoreContract` suite |
| `server:basekt-files` | ✓ works | `install(BaseKtFiles)`: buckets with limits and permissions, streamed uploads, downloads with ranges and safe headers, and `file` fields kept in step with their files |
| `server:basekt-audit` | ✓ works | `install(BaseKtAudit)`: a log of every create, update, delete and action, with who, when and what changed, in the write's own transaction |
| `server:basekt-storage-s3` | ✓ works | an S3 store for AWS, MinIO, R2 and other S3-compatible services: Signature V4 with no SDK, multipart uploads, ranges and presigned URLs |
| `client:basekt-client` | later | a typed multiplatform client SDK: auth, queries, realtime, storage |
| `server:basekt-data-jdbc`, `-mongo` | later | more data adapters |
| `server:basekt-auth-oidc`, `-firebase` | later | more auth providers |

## Installing

Once published, every module is on Maven Central as `io.github.basekt:<module>:<version>`:

```kotlin
dependencies {
    implementation("io.github.basekt:basekt-server:0.1.0")
    implementation("io.github.basekt:basekt-data-exposed:0.1.0")   // or basekt-data-memory
    implementation("io.github.basekt:basekt-auth:0.1.0")
    implementation("io.github.basekt:basekt-admin-api:0.1.0")
    implementation("io.github.basekt:basekt-admin-ui:0.2.0")      // from 0.2.0
    testImplementation("io.github.basekt:basekt-testing:0.1.0")
}
```

The latest `main` is published as `0.2.0-SNAPSHOT` to Maven Central's snapshots repository
(`https://central.sonatype.com/repository/maven-snapshots/`). Releases are cut by pushing a version tag; see
[RELEASING.md](RELEASING.md).

## Building

JDK 17 or later, and Node 22 or later for the admin panel's web build. Node is only needed to build BaseKt: the
panel ships prebuilt in `basekt-admin-ui`'s jar.

```bash
./gradlew build
```

The S3 store's contract suite runs against an in-process fake that checks every request's signature. To also run it
against a real S3-compatible store, pass a bucket it may write to (each run uses a fresh prefix):

```bash
./gradlew :server:basekt-storage-s3:test -Pbasekt.s3.endpoint=http://localhost:9000 -Pbasekt.s3.bucket=basekt -Pbasekt.s3.accessKey=... -Pbasekt.s3.secretKey=...
```

The Exposed adapter's tests run on H2. To also run its contract suite on Postgres, pass a database it may create
tables in:

```bash
./gradlew :server:basekt-data-exposed:test -Pbasekt.postgres.url=jdbc:postgresql://localhost/basekt -Pbasekt.postgres.user=basekt -Pbasekt.postgres.password=basekt
```

The admin panel's source is in `admin/basekt-admin-ui/web`. To work on it with hot reload against a running server
(the sample shop on port 8080 by default, or `BASEKT_SERVER`), run `npm ci` and `npm run dev` there and open
`http://localhost:5173/admin/`.

After changing the admin API's wire classes, regenerate the TypeScript types and commit them. The panel compiles
against them, so a change it doesn't handle fails its build:

```bash
./gradlew :admin:basekt-admin-api:generateTypeScript
```

Maven coordinates, once published: `io.github.basekt:<module>:<version>`.

## Contributing

Read [AGENTS.md](AGENTS.md) for the design rules every module follows, and copy its checklist into each PR.

## License

[Apache-2.0](LICENSE).
