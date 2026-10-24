# Modules

Every module is its own artifact and its own Ktor plugin, published as `io.github.basekt:<module>:<version>`.

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
| `samples:sample-lessons` | ✓ works | a lessons site: Markdown fields where only one allows formulas, pictures kept in storage through file fields, and pages that load KaTeX only for a lesson that has a formula |
| `server:basekt-graphql` | later | a GraphQL API generated at runtime from the same schema, on the same `DataSource` and service layer |
| `server:basekt-realtime` | later | change feeds over WebSockets |
| `server:basekt-storage` | ✓ works | the `BlobStore` SPI: streamed puts and reads of files, byte ranges, and optional listing and presigned URLs |
| `server:basekt-storage-memory`, `-local` | ✓ works | in-memory and local-filesystem stores, both passing the `BlobStoreContract` suite |
| `server:basekt-files` | ✓ works | `install(BaseKtFiles)`: buckets with limits and permissions, streamed uploads, downloads with ranges and safe headers, and `file` fields kept in step with their files |
| `server:basekt-audit` | ✓ works | `install(BaseKtAudit)`: a log of every create, update, delete and action, with who, when and what changed, in the write's own transaction |
| `server:basekt-storage-s3` | ✓ works | an S3 store for AWS, MinIO, R2 and other S3-compatible services: Signature V4 with no SDK, multipart uploads, ranges and presigned URLs |
| `server:basekt-richtext` | ✓ works | `install(BaseKtRichText)`: Markdown fields (`string("body") { markdown { math(); images() } }`) checked on every write for raw HTML, unsafe links and images, bad formulas and size, plus `Markdown.toHtml` and `toPlainText` that render them safely. Formulas are drawn by the client with KaTeX or MathJax |
| `commerce:basekt-commerce`, `commerce:basekt-storefront` | ✓ works | an optional group for selling online. `basekt-storefront` is `install(BaseKtStorefront)`: listing and detail pages and a public JSON API generated from `storefront { }` blocks in the schema, with an availability hook for stock, CSS and page-renderer hooks for the layout, and a per-client rate limit. `basekt-commerce` is an umbrella that pulls in the commerce modules. Payments, holds, idempotency and customers are planned, each as its own module |
| `client:basekt-client` | later | a typed multiplatform client SDK: auth, queries, realtime, storage |
| `server:basekt-data-jdbc`, `-mongo` | later | more data adapters |
| `server:basekt-auth-oidc`, `-firebase` | later | more auth providers |
