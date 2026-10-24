# basekt-storefront

`install(BaseKtStorefront)`: a shop generated from the schema. Declare `storefront { }` on a resource and BaseKt serves its listing and detail pages and a public JSON API. Only the fields the block names are public.

```kotlin
dependencies { implementation("io.github.basekt:basekt-storefront:0.3.0-SNAPSHOT") }
```

In the latest `main`, published as a snapshot until 0.3.0 is released: see [Getting started](https://basekt.github.io/basekt/getting-started/#snapshots).

Builds on: basekt-server.

## Use

```kotlin
resource("products") {
    // fields ...
    storefront {
        title("name"); price("retailPrice"); image("imageUrl")
        filters("brandId", "category")
        variants("skus", by = "size")
        listWhen { eq("active", true) }
    }
}
install(BaseKtStorefront) { siteName = "Shop"; availability = ledgerAvailability }
```

## Good to know

- `Availability` says which variants can be sold now. `css` and a `StorefrontPages` renderer change the look.
- It reads the data source directly, not through roles, so the block is the whole of what is public.

## Documentation

- [Storefront](https://basekt.github.io/basekt/storefront/)
- [All modules](../../README.md#modules)
