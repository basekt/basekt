# Admin API

**The admin API** (`basekt-admin-api`) is JSON only, for a panel to render itself from. It's installed after
`BaseKt`:

```kotlin
install(BaseKtAdmin) { path = "/admin/api" }           // basekt-admin-api
```

| Route under `/admin/api` | What it does |
|---|---|
| `GET /schema` | what the caller may see and do: `"version": 1`, the resources, fields, relations and allowed operations |
| `GET /resources/{name}/records` | a page with a `total`: the same filters, sort and cursor, plus `search` |
| `POST`, `GET`, `PATCH`, `DELETE /resources/{name}/records[/{id}]` | create, read, update and delete |
| `POST /resources/{name}/bulk-delete` | deletes several records and reports each one |
| `GET /resources/{name}/export.csv` | the list as CSV |

It also serves [aggregates](aggregates-reports.md#aggregates) at `GET /admin/api/resources/{name}/aggregate`, with
`search` too, and lists the [actions](actions-workflows.md) each caller may run.

## TypeScript types

Its wire format has TypeScript types in
[`admin/basekt-admin-api/typescript`](https://github.com/basekt/basekt/tree/main/admin/basekt-admin-api/typescript),
generated from the Kotlin classes and checked in CI. The schema contract is additive only within a version. See
[Building](building.md#admin-api-typescript-types) for regenerating them.
