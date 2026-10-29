# Modules

Every module is its own artifact and its own Ktor plugin, published as `io.github.basekt:<module>:<version>`.

| Module | Status | What it is |
|---|---|---|
| `core:basekt-schema` | ✓ works | resources, fields, relations, validation and permissions as data (multiplatform, no Ktor) |
| `server:basekt-server` | ✓ works | `install(BaseKt)`: the schema-driven REST API, permissions, hooks and lifecycle |
| `server:basekt-data` | ✓ works | the `DataSource` SPI: queries, writes, transactions, and optional capabilities such as `Counting` |
| `server:basekt-data-memory` | ✓ works | in-memory adapter, for tests, samples and prototypes |
| `server:basekt-data-exposed` | ✓ works | Exposed/JDBC adapter: Postgres, MySQL, H2. Keyset cursors, one-query `getMany`, savepoints |
| `server:basekt-data-mongo` | ✓ works | MongoDB adapter: a collection per resource, ObjectId ids and references, transactions with nested rollback, `migrate()` for indexes, and every capability; it reads collections another program wrote as they are |
| `server:basekt-data-firestore` | ✓ works | Cloud Firestore adapter: a collection per resource, document ids as record ids, Firestore transactions with nested rollback, aggregation counts, conditional writes and sequences; it reads collections another program wrote as they are, and checks in memory, within a limit, what Firestore can't filter or sort |
| `server:basekt-auth` | ✓ works | Ktor Authentication adapters (JWT, bearer), `PrincipalRoles` mapped to permissions, typed 401 and 403 |
| `server:basekt-auth-firebase` | ✓ works | Firebase ID tokens as an identity, verified against Google's rotating public keys without the Admin SDK: the uid, roles and record-scope attributes from custom claims, beside other providers on the same routes |
| `server:basekt-auth-supabase` | ✓ works | Supabase Auth access tokens as an identity, verified via GoTrue or HMAC-SHA256 JWT secret: user ID, roles and record-scope attributes, beside other providers on the same routes |
| `admin:basekt-admin-api` | ✓ works | `install(BaseKtAdmin)`: a JSON admin API from the schema (per-caller schema, list and search, CRUD, bulk delete, CSV export) and its TypeScript types |
| `testing:basekt-testing` | ✓ works | the `DataSourceContract`, `BlobStoreContract` and `PaymentProviderContract` suites every adapter runs, plus test fixtures |
| `admin:basekt-admin-ui` | ✓ works | `install(BaseKtAdminUi)`: a schema-driven admin panel (React, shadcn/ui and Tailwind, prebuilt into the jar) with a typed Kotlin theme |
| `samples:sample-shop` | ✓ works | a small shop backend: Exposed on H2, JWT and bearer auth, a pricing hook, the admin API, the admin panel, and an OpenAPI document with a TypeScript client checked in CI |
| `samples:sample-lessons` | ✓ works | a lessons site: Markdown fields where only one allows formulas, pictures kept in storage through file fields, and pages that load KaTeX only for a lesson that has a formula |
| `server:basekt-graphql` | later | a GraphQL API generated at runtime from the same schema, on the same `DataSource` and service layer |
| `server:basekt-realtime` | later | change feeds over WebSockets |
| `server:basekt-storage` | ✓ works | the `BlobStore` SPI: streamed puts and reads of files, byte ranges, and optional listing and presigned URLs |
| `server:basekt-storage-memory`, `-local` | ✓ works | in-memory and local-filesystem stores, both passing the `BlobStoreContract` suite |
| `server:basekt-files` | ✓ works | `install(BaseKtFiles)`: buckets with limits and permissions, streamed uploads, downloads with ranges and safe headers, and `file` fields kept in step with their files |
| `server:basekt-audit` | ✓ works | `install(BaseKtAudit)`: a log of every create, update, delete and action, with who, when and what changed, in the write's own transaction |
| `server:basekt-storage-s3` | ✓ works | an S3 store for AWS, MinIO, R2 and other S3-compatible services: Signature V4 with no SDK, multipart uploads, ranges and presigned URLs |
| `server:basekt-openapi` | ✓ works | `install(BaseKtOpenApi)`: an OpenAPI 3.1 document of the REST API generated from the schema, with JSON Schema for every field and the typed errors, served at `/openapi.json` and writable to a file at build time for typed clients |
| `server:basekt-richtext` | ✓ works | `install(BaseKtRichText)`: Markdown fields (`string("body") { markdown { math(); images() } }`) checked on every write for raw HTML, unsafe links and images, bad formulas and size, plus `Markdown.toHtml` and `toPlainText` that render them safely. Formulas are drawn by the client with KaTeX or MathJax |
| `commerce:basekt-commerce`, `commerce:basekt-storefront` | ✓ works | an optional group for selling online. `basekt-storefront` is `install(BaseKtStorefront)`: listing and detail pages and a public JSON API generated from `storefront { }` blocks in the schema, with an availability hook for stock, CSS and page-renderer hooks for the layout, and a per-client rate limit. `basekt-commerce` is an umbrella over `basekt-storefront`, `basekt-payment` and `basekt-payments` |
| `commerce:basekt-payment` | ✓ works | the Ktor-free `PaymentProvider` SPI: exact arbitrary-scale `PaymentAmount` (`PHP` centavos, `USD` cents, 6-decimal `USDT`, 18-decimal `KAIA`), plus `WebhookVerifiable`, `OnChainConfirmable`, `Refundable` and `PayoutCapable` |
| `commerce:basekt-payment-memory` | ✓ works | deterministic in-memory `PaymentProvider` implementing webhooks, on-chain confirmations, refunds and payouts, passing `PaymentProviderContract` |
| `commerce:basekt-payment-paymongo` | ✓ works | PayMongo adapter over `paymongo-kotlin`: Checkout Sessions (`v2` and `v1`), Payment Links, refunds, and constant-time HMAC-SHA256 `Paymongo-Signature` verification (`gcash`, `paymaya`, `qrph`, `card`) |
| `commerce:basekt-payment-paymentwall` | ✓ works | Paymentwall Digital Goods and Subscriptions adapter: Signature v3 (`SHA-256`) widget URLs, sender IP allowlist (`216.127.71.0/24` + exact IPs), `GET`/form-`POST` pingbacks and plain-text `"OK"` listener responses |
| `commerce:basekt-payment-lemonsqueezy` | ✓ works | Lemon Squeezy Merchant-of-Record adapter over Ktor `HttpClient`: JSON:API checkouts, subscription customer-portal URLs, and constant-time HMAC-SHA256 `X-Signature` webhook verification |
| `commerce:basekt-payment-kaia` | ✓ works | Kaia KIP-7 / ERC-20 `USDT` adapter (`0xd077...4fdb` on Mainnet `8217`, or Kairos `1001`) over Ktor `HttpClient`: EIP-681 transfer instructions, strict `Transfer` log confirmation, chunked `eth_getLogs` scanning, and `Mutex`-serialized `PayoutCapable` disbursements with `+15%` gas and native `KAIA` checks |
| `commerce:basekt-payments` | ✓ works | `install(BaseKtPayments)`: `paymentLedger()` and `payable { }` schema DSL, server-read amounts, verified `GET`/`POST` webhooks & pingbacks, on-chain `txHash` confirmation, atomic `(provider, eventKey)` replay protection, role-guarded refunds, payouts and optional background `Transfer` log reconciliation |
| `client:basekt-client` | later | a typed multiplatform client SDK: auth, queries, realtime, storage |
| `server:basekt-data-jdbc`, `-mongo` | later | more data adapters |
| `server:basekt-auth-oidc` | later | more auth providers |
