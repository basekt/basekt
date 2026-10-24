package io.github.basekt.storefront

import io.github.basekt.schema.Resource

/** What a page renderer needs to know about the shop it renders. */
public class StorefrontContext(
    /** The shop's path, such as `/shop`, with a leading slash and no trailing one. Links start with it. */
    public val base: String,
    /** The shop's name. */
    public val siteName: String,
    /** The symbol written before prices. */
    public val currency: String,
    /** The accent colour, as `#rgb` or `#rrggbb`. */
    public val accent: String,
    /** The extra CSS the app configured for the default pages. */
    public val css: String,
)

/** A listing page's data: one page of records and what a shopper may do next. */
public class Listing(
    /** The resource listed. */
    public val resource: Resource,
    /** Its storefront, which names the fields shown. */
    public val storefront: Storefront,
    /** The records of this page, in order. */
    public val items: List<StorefrontItem>,
    /** The cursor of the next page, or `null` on the last. */
    public val nextCursor: String?,
    /** The choices each filter offers: field to (value, label) pairs. Empty for a filter that takes free text. */
    public val options: Map<String, List<Pair<String, String>>>,
    /** What the shopper asked for. */
    public val request: ListingRequest,
)

/** A detail page's data: one record and its variants. */
public class Detail(
    /** The resource the record belongs to. */
    public val resource: Resource,
    /** Its storefront, which names the fields shown. */
    public val storefront: Storefront,
    /** The record. */
    public val item: StorefrontItem,
    /** Its variants, such as sizes, with whether each can be sold now. Empty if the storefront has none. */
    public val variants: List<StorefrontVariant>,
)

/**
 * Renders the shop's pages as HTML. [DefaultPages] is used unless the app sets [BaseKtStorefrontConfig.pages].
 *
 * Every value a page shows comes from records: escape it with [escapeHtml] unless it is known to be safe. Build links
 * from [StorefrontContext.base], and write a record's slug through percent-encoding.
 */
public interface StorefrontPages {
    /** The shop's front page, listing [resources], the ones sold online. */
    public fun index(context: StorefrontContext, resources: List<Resource>): String

    /** A page of records of one resource. */
    public fun listing(context: StorefrontContext, listing: Listing): String

    /** One record, with its variants. */
    public fun detail(context: StorefrontContext, detail: Detail): String
}

/** Writes [text] as HTML text or as a quoted attribute value: `&`, `<`, `>`, `"` and `'` are escaped. */
public fun escapeHtml(text: String): String = buildString(text.length) {
    for (c in text) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&#39;")
        else -> append(c)
    }
}

/**
 * The pages BaseKt ships: a header with the shop's name, a filter bar, a grid of cards and a detail page, styled with
 * CSS variables and a dark mode. Restyle them with [BaseKtStorefrontConfig.css], or reuse their frame in your own
 * renderer with [shell].
 */
public object DefaultPages : StorefrontPages {
    /** Wraps [body], which is HTML, in the default page: its head, styles, the shop's header and `<main>`. */
    public fun shell(context: StorefrontContext, title: String, body: String): String = page(context.theme(), title, context.base, body)

    override fun index(context: StorefrontContext, resources: List<Resource>): String =
        indexPage(context.theme(), context.base, resources)

    override fun listing(context: StorefrontContext, listing: Listing): String = listingPage(
        context.theme(), context.base, listing.resource, listing.storefront,
        ShopPage(listing.items, listing.nextCursor), listing.options, listing.request,
    )

    override fun detail(context: StorefrontContext, detail: Detail): String = detailPage(
        context.theme(), context.base, detail.resource, detail.storefront, ShopDetail(detail.item, detail.variants),
    )

    private fun StorefrontContext.theme(): Theme = Theme(siteName, currency, accent, css)
}
