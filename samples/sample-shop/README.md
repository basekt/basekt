# sample-shop

A small shop backend: Exposed on H2, JWT and bearer sign-in, a hook that keeps the price above the cost, product images on local disk, the admin API and the admin panel with an indigo theme set in Kotlin.

It is a sample and is not published.

## Run it

```bash
./gradlew :samples:sample-shop:run
```

Open `http://localhost:8080/admin` and sign in with `staff-token` (everything) or `customer-token` (browse, place orders).

## Documentation

- [Getting started](https://basekt.github.io/basekt/getting-started/)
- [All modules](../../README.md#modules)
