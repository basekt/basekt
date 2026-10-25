# sample-shop

A small shop backend: Exposed on H2, JWT and bearer sign-in, a hook that keeps the price above the cost, product images on local disk, the admin API, the admin panel with an indigo theme set in Kotlin, and an OpenAPI document at `/openapi.json`.

It is a sample and is not published.

## Run it

```bash
./gradlew :samples:sample-shop:run
```

Open `http://localhost:8080/admin` and sign in with `staff-token` (everything) or `customer-token` (browse, place orders).

## OpenAPI

`openapi/openapi.json` is the document the shop serves, checked in; `OpenApiFileTest` fails the build when it is stale,
and `./gradlew :samples:sample-shop:generateOpenApi` rewrites it. `openapi/src/client.ts` is a typed client made from
it with `openapi-typescript` and `openapi-fetch`, which CI type-checks (`npm ci && npm run check` in `openapi/`).

## Documentation

- [Getting started](https://basekt.github.io/basekt/getting-started/)
- [All modules](../../README.md#modules)
