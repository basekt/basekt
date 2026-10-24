package io.github.basekt.storefront

import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart

/** The look of the generated pages: a name, a currency symbol and an accent colour. Apps may override all three. */
internal class Theme(val siteName: String, val currency: String, val accent: String)

internal fun escape(text: String): String = buildString(text.length) {
    for (c in text) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&#39;")
        else -> append(c)
    }
}

private fun Value?.text(): String = this?.toString().orEmpty()

private fun price(theme: Theme, value: Value?): String = if (value == null) "" else theme.currency + value.text()

/** Only web addresses are used as pictures. */
private fun imageUrl(value: Value?): String? = value.text().takeIf { it.startsWith("https://") || it.startsWith("http://") }

private fun label(name: String): String =
    name.removeSuffix("Id").ifEmpty { name }.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }

private fun page(theme: Theme, title: String, base: String, body: String): String = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escape(title)}</title>
<style>
:root{color-scheme:light dark;--bg:#fff;--fg:#17171a;--muted:#6b6b76;--line:#e4e4e8;--card:#fafafb;--accent:${escape(theme.accent)};--accent-fg:#fff}
@media (prefers-color-scheme:dark){:root{--bg:#101012;--fg:#f2f2f4;--muted:#9a9aa6;--line:#2a2a30;--card:#18181b}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.5 system-ui,sans-serif}
a{color:inherit}header{border-bottom:1px solid var(--line)}
.bar{max-width:72rem;margin:0 auto;padding:1rem;display:flex;gap:1rem;align-items:center;justify-content:space-between}
.bar a{text-decoration:none;font-weight:600}main{max-width:72rem;margin:0 auto;padding:1rem}
form.filters{display:flex;flex-wrap:wrap;gap:.5rem;margin:0 0 1rem}
input,select,button{font:inherit;line-height:1.25;height:2.4rem;padding:0 .75rem;border:1px solid var(--line);border-radius:.5rem;background-color:var(--bg);color:var(--fg)}
select{appearance:none;-webkit-appearance:none;padding-right:2.1rem;cursor:pointer;background-repeat:no-repeat;background-position:right .7rem center;background-size:.8rem;background-image:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'%3E%3Cpath d='M4 6l4 4 4-4' fill='none' stroke='%23888' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E")}
select:hover,input:hover{border-color:var(--muted)}
input:focus-visible,select:focus-visible,button:focus-visible,a:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
button.go{background:var(--accent);color:var(--accent-fg);border-color:var(--accent);cursor:pointer}
.grid{display:grid;gap:1rem;grid-template-columns:repeat(auto-fill,minmax(14rem,1fr));list-style:none;margin:0;padding:0}
.card{display:block;border:1px solid var(--line);border-radius:.6rem;overflow:hidden;background:var(--card);text-decoration:none}
.card img,.ph{width:100%;aspect-ratio:1/1;object-fit:cover;display:block;background:var(--line)}
.card .t{padding:.75rem}.card .t b{display:block}.muted{color:var(--muted)}
.detail{display:grid;gap:2rem;grid-template-columns:repeat(auto-fit,minmax(16rem,1fr))}
.sizes{display:flex;flex-wrap:wrap;gap:.5rem;margin:.5rem 0 1rem}
.size{border:1px solid var(--line);border-radius:.4rem;padding:.4rem .8rem}
.size.out{color:var(--muted);text-decoration:line-through;background:var(--card)}
.pager{margin:1.5rem 0}.empty{padding:3rem 0;text-align:center}
</style></head><body>
<header><div class="bar"><a href="${escape(base)}">${escape(theme.siteName)}</a></div></header>
<main>$body</main></body></html>"""

internal fun indexPage(theme: Theme, base: String, resources: List<Resource>): String = page(
    theme, theme.siteName, base,
    "<h1>${escape(theme.siteName)}</h1><ul>" +
        resources.joinToString("") { "<li><a href=\"${escape(base)}/${escape(it.name.encodeURLPathPart())}\">${escape(label(it.name))}</a></li>" } +
        "</ul>",
)

internal fun listingPage(
    theme: Theme,
    base: String,
    resource: Resource,
    shop: Storefront,
    page: ShopPage,
    options: Map<String, List<Pair<String, String>>>,
    request: ListRequest,
): String {
    val here = "${escape(base)}/${escape(resource.name.encodeURLPathPart())}"
    val form = buildString {
        append("<form class=\"filters\" method=\"get\" action=\"$here\">")
        append("<input type=\"search\" name=\"q\" placeholder=\"Search\" value=\"${escape(request.search.orEmpty())}\">")
        for ((field, choices) in options) {
            val chosen = request.filters[field]
            append("<select name=\"${escape(field)}\" aria-label=\"${escape(label(field))}\"><option value=\"\">${escape(label(field))}</option>")
            for ((value, text) in choices) {
                append("<option value=\"${escape(value)}\"${if (value == chosen) " selected" else ""}>${escape(text)}</option>")
            }
            append("</select>")
        }
        if (shop.price != null) {
            append("<select name=\"sort\" aria-label=\"Sort\">")
            for ((value, text) in listOf("" to "Name", "price" to "Price, low to high", "-price" to "Price, high to low")) {
                append("<option value=\"$value\"${if (value == request.sort.orEmpty()) " selected" else ""}>$text</option>")
            }
            append("</select>")
        }
        append("<button class=\"go\" type=\"submit\">Show</button></form>")
    }
    val cards = if (page.items.isEmpty()) {
        "<p class=\"empty muted\">Nothing here yet.</p>"
    } else {
        "<ul class=\"grid\">" + page.items.joinToString("") { item ->
            val picture = shop.image?.let { imageUrl(item.values[it]) }
            val price = shop.price?.let { price(theme, item.values[it]) }.orEmpty()
            "<li><a class=\"card\" href=\"$here/${escape(item.slug.encodeURLPathPart())}\">" +
                (if (picture != null) "<img src=\"${escape(picture)}\" alt=\"\" loading=\"lazy\">" else "<div class=\"ph\"></div>") +
                "<div class=\"t\"><b>${escape(item.values[shop.title].text())}</b><span class=\"muted\">${escape(price)}</span></div></a></li>"
        } + "</ul>"
    }
    val next = page.nextCursor?.let { cursor ->
        val keep = buildList {
            request.search?.takeIf { it.isNotBlank() }?.let { add("q=${it.encodeURLParameter()}") }
            request.filters.forEach { (k, v) -> add("${k.encodeURLParameter()}=${v.encodeURLParameter()}") }
            request.sort?.takeIf { it.isNotBlank() }?.let { add("sort=${it.encodeURLParameter()}") }
            request.limit?.let { add("limit=$it") }
            add("cursor=${cursor.encodeURLParameter()}")
        }
        "<p class=\"pager\"><a href=\"$here?${escape(keep.joinToString("&"))}\">More</a></p>"
    }.orEmpty()
    return page(theme, "${label(resource.name)} · ${theme.siteName}", base, "<h1>${escape(label(resource.name))}</h1>$form$cards$next")
}

internal fun detailPage(theme: Theme, base: String, resource: Resource, shop: Storefront, detail: ShopDetail): String {
    val item = detail.item
    val picture = shop.image?.let { imageUrl(item.values[it]) }
    val name = item.values[shop.title].text()
    val sizes = if (shop.variants == null) "" else {
        "<h2>${escape(label(shop.variants.by))}</h2><div class=\"sizes\">" + detail.variants.joinToString("") {
            "<span class=\"size${if (it.available) "" else " out"}\">${escape(it.label.text())}</span>"
        } + "</div>"
    }
    val body = "<p><a class=\"muted\" href=\"${escape(base)}/${escape(resource.name.encodeURLPathPart())}\">&larr; ${escape(label(resource.name))}</a></p>" +
        "<div class=\"detail\"><div>" +
        (if (picture != null) "<img class=\"card\" style=\"width:100%\" src=\"${escape(picture)}\" alt=\"${escape(name)}\">" else "<div class=\"ph card\"></div>") +
        "</div><div><h1>${escape(name)}</h1>" +
        (shop.price?.let { "<p><b>${escape(price(theme, item.values[it]))}</b></p>" }.orEmpty()) +
        (shop.summary?.let { "<p class=\"muted\">${escape(item.values[it].text())}</p>" }.orEmpty()) +
        sizes + "</div></div>"
    return page(theme, "$name · ${theme.siteName}", base, body)
}
