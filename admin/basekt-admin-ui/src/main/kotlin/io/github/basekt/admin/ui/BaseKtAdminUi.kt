package io.github.basekt.admin.ui

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.log
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How the panel starts before the viewer picks a mode; the viewer's choice is then remembered in their browser. */
@Serializable
public enum class ThemeMode {
    /** Light colours. */
    @SerialName("light") Light,

    /** Dark colours. */
    @SerialName("dark") Dark,

    /** Whatever the viewer's operating system prefers. */
    @SerialName("system") System,
}

/** The host app's name and logo, shown in the panel's sidebar and title. */
public class BrandConfig internal constructor(config: ApplicationConfig) {
    /** The name. `basekt.adminUi.brand.name`, "BaseKt" by default. */
    public var name: String = config.propertyOrNull("brand.name")?.getString() ?: "BaseKt"

    /** A logo's URL: a path on this server, an `https:` URL or a `data:image/` URL. `basekt.adminUi.brand.logo`. */
    public var logo: String? = config.propertyOrNull("brand.logo")?.getString()
}

/**
 * The configuration of [BaseKtAdminUi]. Settings can come from the `basekt.adminUi` section of the application's
 * configuration; values set in the `install` block win. The theme is set in Kotlin only, with [theme].
 */
public class BaseKtAdminUiConfig internal constructor(config: ApplicationConfig) {
    /** Where the panel is served. `basekt.adminUi.path`, `/admin` by default. */
    public var path: String = config.propertyOrNull("path")?.getString() ?: "/admin"

    /** Where `BaseKtAdmin`'s JSON API is mounted. `basekt.adminUi.apiPath`, `/admin/api` by default. */
    public var apiPath: String = config.propertyOrNull("apiPath")?.getString() ?: "/admin/api"

    /**
     * Where `BaseKtFiles` serves files, for file fields' uploads and previews. `basekt.adminUi.filesPath`, `/files` by
     * default. Without `BaseKtFiles` installed, file fields can still show their keys.
     */
    public var filesPath: String = config.propertyOrNull("filesPath")?.getString() ?: "/files"

    /** The mode the panel starts in. `basekt.adminUi.defaultMode` (light, dark or system), system by default. */
    public var defaultMode: ThemeMode = config.propertyOrNull("defaultMode")?.getString()
        ?.let { value -> ThemeMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } }
        ?: ThemeMode.System

    /** The name and logo. */
    public val brand: BrandConfig = BrandConfig(config)

    internal val themeBuilder = ThemeBuilder()

    /** Sets the brand's name and logo. */
    public fun brand(block: BrandConfig.() -> Unit) {
        brand.block()
    }

    /** Sets the theme; what it leaves out keeps [Theme.Default]'s value. */
    public fun theme(block: ThemeBuilder.() -> Unit) {
        themeBuilder.block()
    }
}

/**
 * Serves BaseKt's admin panel under [BaseKtAdminUiConfig.path]: a React app, prebuilt into this jar, that renders
 * every screen from the JSON admin API's `GET {apiPath}/schema`. Adding a resource never needs a change here.
 *
 * | Route under `path` | What it serves |
 * |---|---|
 * | `/` and any other path | the shell page, so the panel's own links work when opened directly |
 * | `/theme.css` | the [Theme]'s CSS variables |
 * | `/assets/…` | the panel's scripts and styles, cached for a year since their names change with their content |
 *
 * The panel calls the API with the browser's cookies and, once a viewer signs in with one, a bearer token. Install
 * `BaseKtAdmin` from `basekt-admin-api` for the API itself; this module doesn't depend on it.
 */
public val BaseKtAdminUi: ApplicationPlugin<BaseKtAdminUiConfig> = createApplicationPlugin(
    name = "BaseKtAdminUi",
    configurationPath = "basekt.adminUi",
    createConfiguration = ::BaseKtAdminUiConfig,
) {
    val path = "/" + pluginConfig.path.trim('/')
    val apiPath = "/" + pluginConfig.apiPath.trim('/')
    require(path != "/") { "BaseKtAdminUi's path must not be '/': the panel answers every path under it." }
    require(apiPath != path) { "BaseKtAdminUi's path and apiPath must differ, not both be '$path'." }
    val logo = pluginConfig.brand.logo?.trim()?.takeIf { it.isNotEmpty() }
    require(logo == null || logo.startsWith("/") || logo.startsWith("https://") || logo.startsWith("data:image/")) {
        "The brand logo must be a path on this server, an https: URL or a data:image/ URL, not '$logo'."
    }
    val shell = ShellConfig(
        path = path,
        apiPath = apiPath,
        filesPath = "/" + pluginConfig.filesPath.trim('/'),
        brand = ShellBrand(pluginConfig.brand.name, logo),
        defaultMode = pluginConfig.defaultMode,
    )
    val entry = PanelAssets.load(application.environment.classLoader)
    val themeCss = ThemeCss.write(Theme.Default + pluginConfig.themeBuilder.build())
    val apiPrefix = "$apiPath/"
    val assetsPrefix = "$path/assets/"

    application.routing {
        route(path) {
            get { respondShell(shell, entry) }
            get("theme.css") {
                call.response.header(HttpHeaders.CacheControl, "no-cache")
                call.respondText(themeCss, ContentType.Text.CSS)
            }
            staticResources("assets", "${PanelAssets.ROOT}/assets", index = null) {
                cacheControl { listOf(CacheControl.MaxAge(maxAgeSeconds = ONE_YEAR, visibility = CacheControl.Visibility.Public)) }
            }
            get("{...}") {
                // The admin API and missing assets stay 404s rather than turning into the shell page.
                val requested = call.request.path()
                if (requested.startsWith(apiPrefix) || requested == apiPath || requested.startsWith(assetsPrefix)) return@get
                respondShell(shell, entry)
            }
        }
    }

    on(MonitoringEvent(ApplicationStarted)) { app ->
        app.log.info("BaseKt admin panel serves $path, reading the admin API at $apiPath")
    }
}

private suspend fun RoutingContext.respondShell(config: ShellConfig, entry: PanelAssets.Entry) {
    call.response.header(HttpHeaders.CacheControl, "no-cache")
    call.respondShellPage(config, entry)
}

private const val ONE_YEAR = 365 * 24 * 60 * 60
