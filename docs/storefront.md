# Storefront

**The storefront** (`basekt-storefront`) sells records online without anyone writing a page. Declare `storefront { }`
on a resource, and BaseKt serves its listing and detail pages and a public JSON API, the way the admin panel is built
from the schema. It is optional: an app that doesn't depend on it gets none of it.

```kotlin
resource("products") {
    belongsTo("brand", "brands")
    string("name") { required() }
    string("colorway")
    enum("category", listOf("running", "court", "trail"))
    string("photo")
    decimal("price") { required() }
    decimal("costPrice")                       // never shown: it isn't named below
    bool("active")
    hasMany("sizes", "sizes", foreignField = "productId")

    storefront {
        title("name"); summary("colorway"); price("price"); image("photo")
        filters("brandId", "category")         // fields, so a reference is named by its field
        variants("sizes", by = "label")        // the choices on a detail page
        listWhen { eq("active", true) }        // only these records are listed
    }
}

install(BaseKt) { /* ... */ }
install(BaseKtStorefront) { siteName = "Crumb"; currency = "$" }
```

## What is public

Only the fields the block names, and only for records that meet `listWhen`. Roles don't apply: the block is the whole
of what is public, so a visitor needs no sign-in. Resources without a block aren't served at all.

The block is checked when the app starts. A title that isn't a field, a hidden field, a price that isn't a number, a
filter on a field that doesn't exist or a `variants` relation that isn't a has-many are all refused, with every problem
listed. `Schema.storefrontProblems()` returns the same list for tests.

| Block | What it does |
|---|---|
| `title(field)` | the record's name. Required |
| `slug(field)` | the field that names a record in its address. `id` unless set; use a unique field for nicer links |
| `price(field)`, `image(field)`, `summary(field)` | a number, the address of a picture, and a short description |
| `filters(fields...)` | the fields a shopper may narrow a listing by |
| `variants(relation, by)` | the records of a has-many relation, such as sizes, told apart by one field |
| `listWhen { eq(field, value) }` | conditions a record must meet to be listed |

## Routes

Mounted at `path`, `/shop` unless set.

| Route | What it does |
|---|---|
| `GET /` | the resources sold |
| `GET /{resource}` | a listing page: `q` searches the title, filters are named by field, `sort` is `title`, `-title`, `price` or `-price`, `limit` and `cursor` page |
| `GET /{resource}/{slug}` | a detail page with its variants |
| `GET /api/{resource}` | the same listing as JSON: `items` and `nextCursor` |
| `GET /api/{resource}/{slug}` | one record as JSON, with its `variants` |

A query parameter that isn't one of the declared filters is ignored, so a visitor can't probe other fields.

## Stock

The storefront doesn't know what stock is. Say which variants can be sold now with `availability`, and it marks the rest
sold out:

```kotlin
install(BaseKtStorefront) {
    availability = Availability { resource, variants ->
        variants.mapNotNull { (it[Resource.ID] as? Value.Text)?.value }.filter { stock.has(it) }.toSet()
    }
}
```

A shop that tracks one pair at a time, by count, or not at all answers from its own data. Everything is available
unless set.

## Look and layout

Three levels, from least to most work.

**Settings.** `siteName`, `currency` and `accent` (a `#rgb` or `#rrggbb` colour), or the `basekt.storefront` section of
`application.conf`.

**CSS.** `css` is appended after the default styles, so it wins. The defaults use variables, so a restyle is usually a
few overrides:

```kotlin
install(BaseKtStorefront) {
    css = """
        :root { --accent: #0a7d4f; --card: #fff; }
        .card { border-radius: 0; }
        h1 { letter-spacing: -.02em; }
    """.trimIndent()
}
```

The variables are `--bg`, `--fg`, `--muted`, `--line`, `--card` and `--accent`, with a dark set under
`prefers-color-scheme`. CSS containing `</style` is refused.

**Markup.** `pages` takes your own `StorefrontPages`, which renders the three pages as HTML from plain data. There is
no template engine:

```kotlin
class MyPages : StorefrontPages {
    override fun index(context: StorefrontContext, resources: List<Resource>) = DefaultPages.shell(context, "Home", "...")

    override fun listing(context: StorefrontContext, listing: Listing) = DefaultPages.shell(
        context, "Shop",
        listing.items.joinToString("") { "<p>${escapeHtml(it.values["name"].toString())}</p>" },
    )

    override fun detail(context: StorefrontContext, detail: Detail) = /* ... */
}
install(BaseKtStorefront) { pages = MyPages() }
```

`DefaultPages.shell` wraps your body in the default head, styles and header; leave it out for a page of your own.
Record text must be escaped with `escapeHtml`. Build links from `context.base`.

**Your own front end.** The JSON API is public, so a separate app can use it instead of the generated pages.

## Safety

Pages escape everything records hold, use only `http` and `https` pictures, and set `Content-Security-Policy`,
`X-Content-Type-Options` and `Referrer-Policy` headers. The default policy allows no scripts and no outside styles; a
renderer that needs web fonts or a script widens `contentSecurityPolicy`. Each client may make 120 requests a minute
unless `rate.requests` and `rate.windowSeconds` say otherwise; `0` turns the limit off.

## Writing your own block

`storefront { }` isn't built into the schema. It is a few lines on top of **extensions**: a resource can carry settings
under a key of its own, and the schema keeps them as JSON without knowing what they mean.

```kotlin
@Serializable data class Shelf(val label: String)

fun Extensible.shelf(label: String) = extension("warehouse.shelf", Shelf(label))   // in your module
val Resource.shelf: Shelf? get() = extension<Shelf>("warehouse.shelf")            // reading it back
```

`Extensible` is implemented by the resource builder and by typed models. Settings survive the schema's JSON form, and
the schema checks only that a key is well formed. Prefix keys with your module's name so two modules don't clash.

## Related

- [Schema and the REST API](schema.md)
- [Admin panel and themes](admin-panel.md), the same idea for the back office
