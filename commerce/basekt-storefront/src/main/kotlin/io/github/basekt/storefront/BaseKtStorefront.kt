package io.github.basekt.storefront

import io.github.basekt.server.BaseKt
import io.github.basekt.server.baseKt
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.log
import io.ktor.server.application.pluginOrNull
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * The configuration of [BaseKtStorefront]. Settings can come from the `basekt.storefront` section of the application's
 * configuration; values set in the `install` block win.
 */
public class BaseKtStorefrontConfig internal constructor(config: ApplicationConfig) {
    /** Where the shop is mounted. `basekt.storefront.path`, `/shop` by default. */
    public var path: String = config.propertyOrNull("path")?.getString() ?: "/shop"

    /** The name shown in the pages' header and title. `basekt.storefront.siteName`, `Shop` by default. */
    public var siteName: String = config.propertyOrNull("siteName")?.getString() ?: "Shop"

    /** A symbol written before prices. `basekt.storefront.currency`, none by default. */
    public var currency: String = config.propertyOrNull("currency")?.getString() ?: ""

    /** The colour of buttons, as `#rgb` or `#rrggbb`. `basekt.storefront.accent`, `#2d5bff` by default. */
    public var accent: String = config.propertyOrNull("accent")?.getString() ?: "#2d5bff"

    /** The page size when a listing gives no `limit`. `basekt.storefront.pageSize.default`, 24 by default. */
    public var defaultPageSize: Int = config.propertyOrNull("pageSize.default")?.getString()?.toInt() ?: 24

    /** The largest `limit` a listing may ask for. `basekt.storefront.pageSize.max`, 100 by default. */
    public var maxPageSize: Int = config.propertyOrNull("pageSize.max")?.getString()?.toInt() ?: 100

    /** The most requests one client may make in each [rateWindowSeconds]; 0 turns the limit off. `basekt.storefront.rate.requests`, 120 by default. */
    public var rateRequests: Int = config.propertyOrNull("rate.requests")?.getString()?.toInt() ?: 120

    /** The length of the rate window, in seconds. `basekt.storefront.rate.windowSeconds`, 60 by default. */
    public var rateWindowSeconds: Int = config.propertyOrNull("rate.windowSeconds")?.getString()?.toInt() ?: 60

    /** Says which variants can be sold now. Everything can, unless set. */
    public var availability: Availability = Availability.Always

    /**
     * Extra CSS appended after the default pages' styles, to restyle them: fonts, spacing, colours. The defaults use
     * the variables `--bg`, `--fg`, `--muted`, `--line`, `--card` and `--accent`. Can't contain `</style`.
     * `basekt.storefront.css`, none by default.
     */
    public var css: String = config.propertyOrNull("css")?.getString() ?: ""

    /**
     * Renders the pages. [DefaultPages] unless set; supply your own to change the markup. It is also used by the
     * default pages' [css]: a custom renderer takes the styles with it unless it calls [DefaultPages.shell].
     */
    public var pages: StorefrontPages = DefaultPages

    /**
     * The `Content-Security-Policy` of every response. The default allows no scripts, no outside styles and only
     * pictures from the web; a renderer that needs more, such as web fonts or a script, widens it here.
     * `basekt.storefront.contentSecurityPolicy`.
     */
    public var contentSecurityPolicy: String = config.propertyOrNull("contentSecurityPolicy")?.getString() ?: DEFAULT_CSP
}

private const val DEFAULT_CSP: String =
    "default-src 'none'; style-src 'unsafe-inline'; img-src http: https: data:; base-uri 'none'; form-action 'self'"

/**
 * Serves a shop generated from the schema's `storefront { }` blocks, to anyone, under [BaseKtStorefrontConfig.path]:
 * HTML pages and a public JSON API. Nothing else of the schema is exposed, and callers' roles don't apply, because
 * the blocks are the whole of what is public.
 *
 * | Route | What it does |
 * |---|---|
 * | `GET /` | the list of resources sold |
 * | `GET /{resource}` | a listing page: `q`, filters by name, `sort`, `limit`, `cursor` |
 * | `GET /{resource}/{slug}` | a detail page with its variants |
 * | `GET /api/{resource}` | the same listing as JSON: `items` and `nextCursor` |
 * | `GET /api/{resource}/{slug}` | one record as JSON, with its `variants` |
 *
 * Install [BaseKt] first. A schema whose storefronts don't fit their resources is refused at install.
 */
public val BaseKtStorefront: ApplicationPlugin<BaseKtStorefrontConfig> = createApplicationPlugin(
    name = "BaseKtStorefront",
    configurationPath = "basekt.storefront",
    createConfiguration = ::BaseKtStorefrontConfig,
) {
    checkNotNull(application.pluginOrNull(BaseKt)) { "Install BaseKt before BaseKtStorefront." }
    val runtime = application.baseKt
    val base = "/" + pluginConfig.path.trim('/')
    require(base != "/" && base != runtime.basePath) {
        "BaseKtStorefront's path '$base' must differ from '/' and from BaseKt's '${runtime.basePath}'."
    }
    require(pluginConfig.maxPageSize >= 1 && pluginConfig.defaultPageSize in 1..pluginConfig.maxPageSize) {
        "Storefront page sizes must satisfy 1 <= default (${pluginConfig.defaultPageSize}) <= max (${pluginConfig.maxPageSize})."
    }
    require(Regex("#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})").matches(pluginConfig.accent)) {
        "The storefront's accent '${pluginConfig.accent}' must be a colour like #2d5bff."
    }
    require(pluginConfig.rateRequests >= 0 && pluginConfig.rateWindowSeconds >= 1) {
        "The storefront's rate needs requests >= 0 and windowSeconds >= 1."
    }
    require(!pluginConfig.css.contains("</style", ignoreCase = true)) { "The storefront's css can't contain '</style'." }
    require('\n' !in pluginConfig.contentSecurityPolicy && '\r' !in pluginConfig.contentSecurityPolicy) {
        "The storefront's contentSecurityPolicy must be on one line."
    }
    val problems = runtime.schema.storefrontProblems()
    require(problems.isEmpty()) { "The schema's storefronts are not valid:\n" + problems.joinToString("\n") { "- $it" } }

    val shop = Shop(runtime.schema, runtime.dataSource, pluginConfig.availability, pluginConfig.defaultPageSize, pluginConfig.maxPageSize)
    val context = StorefrontContext(base, pluginConfig.siteName, pluginConfig.currency, pluginConfig.accent, pluginConfig.css)
    val pages = pluginConfig.pages
    val headers = shopHeaders + ("Content-Security-Policy" to pluginConfig.contentSecurityPolicy)
    val limiter = RateLimiter(pluginConfig.rateRequests, pluginConfig.rateWindowSeconds * 1000L)

    application.routing {
        route(base) {
            get("api/{resource}") {
                json(limiter, headers) {
                    val resource = shop.resource(call.parameters["resource"]!!) ?: throw ShopException(404, "Nothing is sold under that name.")
                    val page = shop.list(resource, call.listRequest(resource.storefront!!))
                    JsonObject(mapOf("items" to JsonArray(page.items.map { it.toJson() }), "nextCursor" to JsonPrimitive(page.nextCursor)))
                }
            }
            get("api/{resource}/{slug}") {
                json(limiter, headers) {
                    val resource = shop.resource(call.parameters["resource"]!!) ?: throw ShopException(404, "Nothing is sold under that name.")
                    val found = shop.detail(resource, call.parameters["slug"]!!) ?: throw ShopException(404, "No such record.")
                    val variants = JsonArray(
                        found.variants.map {
                            JsonObject(mapOf("id" to JsonPrimitive(it.id), "label" to it.label.toJson(), "available" to JsonPrimitive(it.available)))
                        },
                    )
                    JsonObject(found.item.toJson() + ("variants" to variants))
                }
            }

            get {
                html(limiter, headers) { pages.index(context, shop.resources) }
            }
            get("{resource}") {
                html(limiter, headers) {
                    val resource = shop.resource(call.parameters["resource"]!!) ?: throw ShopException(404, "Nothing is sold under that name.")
                    val shopDef = resource.storefront!!
                    val request = call.listRequest(shopDef)
                    val page = shop.list(resource, request)
                    pages.listing(context, Listing(resource, shopDef, page.items, page.nextCursor, shop.filterOptions(resource), request))
                }
            }
            get("{resource}/{slug}") {
                html(limiter, headers) {
                    val resource = shop.resource(call.parameters["resource"]!!) ?: throw ShopException(404, "Nothing is sold under that name.")
                    val found = shop.detail(resource, call.parameters["slug"]!!) ?: throw ShopException(404, "No such record.")
                    pages.detail(context, Detail(resource, resource.storefront!!, found.item, found.variants))
                }
            }
        }
    }

    on(MonitoringEvent(ApplicationStarted)) { app ->
        app.log.info("BaseKt storefront sells ${shop.resources.size} resources under $base")
    }
}

private fun ApplicationCall.listRequest(shop: Storefront): ListingRequest {
    val query = request.queryParameters
    return ListingRequest(
        search = query["q"],
        filters = shop.filters.mapNotNull { field -> query[field]?.takeIf { it.isNotEmpty() }?.let { field to it } }.toMap(),
        sort = query["sort"],
        limit = query["limit"]?.let { it.toIntOrNull() ?: throw ShopException(400, "'limit' needs a whole number.") },
        cursor = query["cursor"]?.takeIf { it.isNotEmpty() },
    )
}

private val shopHeaders = mapOf(
    "X-Content-Type-Options" to "nosniff",
    "Referrer-Policy" to "same-origin",
    "Cache-Control" to "public, max-age=30",
)

/** Answers 429 and returns `false` if the caller has used up its requests. */
private suspend fun RoutingContext.withinRate(limiter: RateLimiter): Boolean {
    val retry = limiter.retryAfterSeconds(call.request.origin.remoteHost) ?: return true
    call.response.header(HttpHeaders.RetryAfter, retry.toString())
    call.respondText("""{"error":"Too many requests."}""", ContentType.Application.Json, HttpStatusCode.TooManyRequests)
    return false
}

private suspend fun RoutingContext.json(limiter: RateLimiter, headers: Map<String, String>, block: suspend () -> JsonObject) {
    if (!withinRate(limiter)) return
    headers.forEach { (k, v) -> call.response.header(k, v) }
    try {
        call.respondText(block().toString(), ContentType.Application.Json)
    } catch (e: ShopException) {
        call.respondText(
            JsonObject(mapOf("error" to JsonPrimitive(e.message))).toString(),
            ContentType.Application.Json,
            HttpStatusCode.fromValue(e.status),
        )
    }
}

private suspend fun RoutingContext.html(limiter: RateLimiter, headers: Map<String, String>, block: suspend () -> String) {
    if (!withinRate(limiter)) return
    headers.forEach { (k, v) -> call.response.header(k, v) }
    try {
        call.respondText(block(), ContentType.Text.Html)
    } catch (e: ShopException) {
        call.respondText(
            "<!doctype html><meta charset=\"utf-8\"><title>${e.status}</title><p>${escape(e.message.orEmpty())}</p>",
            ContentType.Text.Html,
            HttpStatusCode.fromValue(e.status),
        )
    }
}

/** A fixed window per client, kept in memory. [retryAfterSeconds] is `null` while the client is within its limit. */
internal class RateLimiter(private val requests: Int, private val windowMillis: Long, private val now: () -> Long = System::currentTimeMillis) {
    private class Window(val start: Long, var count: Int)

    private val windows = ConcurrentHashMap<String, Window>()

    fun retryAfterSeconds(client: String): Long? {
        if (requests == 0) return null
        val time = now()
        if (windows.size > 10_000) windows.entries.removeIf { time - it.value.start >= windowMillis }
        var retry: Long? = null
        windows.compute(client) { _, current ->
            val window = if (current == null || time - current.start >= windowMillis) Window(time, 0) else current
            window.count++
            if (window.count > requests) retry = maxOf(1L, (window.start + windowMillis - time + 999) / 1000)
            window
        }
        return retry
    }
}
