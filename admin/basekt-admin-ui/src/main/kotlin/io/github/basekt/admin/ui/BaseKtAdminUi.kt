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
 * A login route of the host app that the panel's sign-in screen calls with a name and a password, such as
 * `POST /auth/login` with `{"email": …, "password": …}` answering `{"token": …}`. The panel keeps the token like one a
 * viewer pastes, and sends it as a bearer token.
 *
 * The answer may also hold a refresh token and the token's life in seconds, as in
 * `{"token": …, "refreshToken": …, "expiresIn": 900}`. With a [refreshPath], the panel then posts
 * `{"refreshToken": …}` there before the token expires, takes the same shape back, and signs the viewer out when the
 * route refuses. Without an `expiresIn`, the expiry is read from the token when it is a JWT. Settings come from
 * `basekt.adminUi.login`.
 */
public class LoginConfig internal constructor(config: ApplicationConfig) {
    /**
     * The route's path on this server, such as `/auth/login`, or `null` to sign in with a pasted token only. It must be
     * a path, not a URL: the password only ever goes to the panel's own server. `basekt.adminUi.login.path`.
     */
    public var path: String? = config.propertyOrNull("login.path")?.getString()

    /** The JSON property the name goes in. `basekt.adminUi.login.usernameField`, `email` by default. */
    public var usernameField: String = config.propertyOrNull("login.usernameField")?.getString() ?: "email"

    /** How the form labels the name. `basekt.adminUi.login.usernameLabel`, `Email` by default. */
    public var usernameLabel: String = config.propertyOrNull("login.usernameLabel")?.getString() ?: "Email"

    /** The JSON property the password goes in. `basekt.adminUi.login.passwordField`, `password` by default. */
    public var passwordField: String = config.propertyOrNull("login.passwordField")?.getString() ?: "password"

    /** The JSON property of the answer that holds the bearer token. `basekt.adminUi.login.tokenField`, `token` by default. */
    public var tokenField: String = config.propertyOrNull("login.tokenField")?.getString() ?: "token"

    /**
     * The host's refresh route on this server, such as `/auth/refresh`, or `null` to keep each token until it is
     * refused. Only with one does the panel keep a refresh token, and it sends it nowhere else.
     * `basekt.adminUi.login.refreshPath`.
     */
    public var refreshPath: String? = config.propertyOrNull("login.refreshPath")?.getString()

    /**
     * The JSON property that holds the refresh token, in the answers and in the refresh call's body.
     * `basekt.adminUi.login.refreshTokenField`, `refreshToken` by default.
     */
    public var refreshTokenField: String = config.propertyOrNull("login.refreshTokenField")?.getString() ?: "refreshToken"

    /** The JSON property of the answers that holds the token's life in seconds. `basekt.adminUi.login.expiresInField`, `expiresIn` by default. */
    public var expiresInField: String = config.propertyOrNull("login.expiresInField")?.getString() ?: "expiresIn"
}

/**
 * Sign-in through Firebase Authentication, from the project's Web config. The panel runs Firebase's sign-in, sends
 * the user's ID token as the bearer token, and has Firebase refresh it; the host checks it like any Firebase ID token.
 * On when [apiKey] is set. Settings come from `basekt.adminUi.firebase`.
 */
public class FirebaseSignInConfig internal constructor(config: ApplicationConfig) {
    /** The Web config's API key, which names the project and is public by design. `basekt.adminUi.firebase.apiKey`. */
    public var apiKey: String? = config.propertyOrNull("firebase.apiKey")?.getString()

    /** The Web config's auth domain, such as `my-project.firebaseapp.com`. `basekt.adminUi.firebase.authDomain`. */
    public var authDomain: String? = config.propertyOrNull("firebase.authDomain")?.getString()

    /** The Web config's project id, if any. `basekt.adminUi.firebase.projectId`. */
    public var projectId: String? = config.propertyOrNull("firebase.projectId")?.getString()

    /**
     * The Firebase provider ids the sign-in screen offers: `password` for an email and a password, and ones such as
     * `google.com`, `microsoft.com`, `oidc.<name>` or `saml.<name>` for a popup. Each must be enabled in the Firebase
     * project. `basekt.adminUi.firebase.providers`, `["password"]` by default.
     */
    public var providers: List<String> = config.propertyOrNull("firebase.providers")?.getList() ?: listOf("password")
}

/**
 * Sign-in through an OpenID Connect issuer, with the authorization code flow and PKCE. The issuer sends the viewer
 * back to `{path}/signed-in`, which must be a redirect URI of the [clientId]; the panel then sends the ID token as the
 * bearer token, and refreshes it with the issuer's refresh token (ask for `offline_access`). On when [issuer] is set.
 * Settings come from `basekt.adminUi.oidc`.
 */
public class OidcSignInConfig internal constructor(config: ApplicationConfig) {
    /** The issuer's `https` URL, where its `.well-known/openid-configuration` is found. `basekt.adminUi.oidc.issuer`. */
    public var issuer: String? = config.propertyOrNull("oidc.issuer")?.getString()

    /** The panel's public client id at the issuer. `basekt.adminUi.oidc.clientId`. */
    public var clientId: String? = config.propertyOrNull("oidc.clientId")?.getString()

    /**
     * The scopes asked for, which must include `openid`. `basekt.adminUi.oidc.scopes`,
     * `["openid", "profile", "email", "offline_access"]` by default.
     */
    public var scopes: List<String> = config.propertyOrNull("oidc.scopes")?.getList()
        ?: listOf("openid", "profile", "email", "offline_access")

    /** What the sign-in button names, as in "Continue with {label}". `basekt.adminUi.oidc.label`, `single sign-on` by default. */
    public var label: String = config.propertyOrNull("oidc.label")?.getString() ?: "single sign-on"
}

/**
 * Sign-in through Supabase Auth (GoTrue), from the project's public URL and anon key. The panel signs viewers in with
 * an email and password or an OAuth redirect back to `{path}/signed-in`, sends the Supabase access token as the bearer
 * token, and refreshes it before it expires; the host checks it with `basekt-auth-supabase`. On when [url] is set.
 * Settings come from `basekt.adminUi.supabase`.
 */
public class SupabaseSignInConfig internal constructor(config: ApplicationConfig) {
    /** The Supabase project URL, such as `https://xyzcompany.supabase.co`. `basekt.adminUi.supabase.url`. */
    public var url: String? = config.propertyOrNull("supabase.url")?.getString()

    /** The project's public anon key, sent as `apikey` to Supabase Auth. `basekt.adminUi.supabase.anonKey`. */
    public var anonKey: String? = config.propertyOrNull("supabase.anonKey")?.getString()

    /**
     * The sign-in methods offered: `password` for an email and a password, and OAuth provider names such as `google`,
     * `github`, `discord` or `microsoft` for an OAuth redirect. `basekt.adminUi.supabase.providers`, `["password"]` by
     * default.
     */
    public var providers: List<String> = config.propertyOrNull("supabase.providers")?.getList() ?: listOf("password")
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

    /**
     * Classpath directory holding a Vite build (`.vite/manifest.json` and `assets/`). Defaults to `basekt-admin-ui`;
     * host apps that build an extended panel with custom views point this at their own resource directory.
     */
    public var assetsRoot: String = config.propertyOrNull("assetsRoot")?.getString() ?: PanelAssets.ROOT

    /** The mode the panel starts in. `basekt.adminUi.defaultMode` (light, dark or system), system by default. */
    public var defaultMode: ThemeMode = config.propertyOrNull("defaultMode")?.getString()
        ?.let { value -> ThemeMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } }
        ?: ThemeMode.System

    /** The name and logo. */
    public val brand: BrandConfig = BrandConfig(config)

    /** The host app's login route, for signing in with a name and a password. Off until [LoginConfig.path] is set. */
    public val login: LoginConfig = LoginConfig(config)

    /** Sign-in through Firebase Authentication. Off until [FirebaseSignInConfig.apiKey] is set. */
    public val firebase: FirebaseSignInConfig = FirebaseSignInConfig(config)

    /** Sign-in through an OpenID Connect issuer. Off until [OidcSignInConfig.issuer] is set. */
    public val oidc: OidcSignInConfig = OidcSignInConfig(config)

    /** Sign-in through Supabase Auth. Off until [SupabaseSignInConfig.url] is set. */
    public val supabase: SupabaseSignInConfig = SupabaseSignInConfig(config)

    internal var siteLink: SiteLink? = config.propertyOrNull("siteLink.url")?.getString()
        ?.let { SiteLink(config.propertyOrNull("siteLink.label")?.getString() ?: "Open site", it) }

    internal val themeBuilder = ThemeBuilder()

    /** Sets the brand's name and logo. */
    public fun brand(block: BrandConfig.() -> Unit) {
        brand.block()
    }

    /**
     * Adds a link to the host app's own site to the panel's top bar, such as `siteLink("Open site", "/")`. [url] is a
     * path on this server or an `http` or `https` address. Also `basekt.adminUi.siteLink.label` and `.url`.
     */
    public fun siteLink(label: String = "Open site", url: String) {
        siteLink = SiteLink(label, url)
    }

    /** Lets viewers sign in with a name and a password through the host app's login route. See [LoginConfig]. */
    public fun login(block: LoginConfig.() -> Unit) {
        login.block()
    }

    /** Lets viewers sign in through Firebase Authentication. See [FirebaseSignInConfig]. */
    public fun firebase(block: FirebaseSignInConfig.() -> Unit) {
        firebase.block()
    }

    /** Lets viewers sign in through an OpenID Connect issuer. See [OidcSignInConfig]. */
    public fun oidc(block: OidcSignInConfig.() -> Unit) {
        oidc.block()
    }

    /** Lets viewers sign in through Supabase Auth. See [SupabaseSignInConfig]. */
    public fun supabase(block: SupabaseSignInConfig.() -> Unit) {
        supabase.block()
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
 * The panel calls the API with the browser's cookies and, once a viewer signs in, a bearer token: one they paste, one
 * the host's [login][BaseKtAdminUiConfig.login] route answers for their name and password, or an identity provider's
 * ID token ([firebase][BaseKtAdminUiConfig.firebase] or [oidc][BaseKtAdminUiConfig.oidc]). Tokens that expire are
 * refreshed before they do. Install `BaseKtAdmin` from `basekt-admin-api` for the API itself; this module doesn't
 * depend on it. Every setting is checked when the plugin is installed, and every problem is listed at once.
 */
public val BaseKtAdminUi: ApplicationPlugin<BaseKtAdminUiConfig> = createApplicationPlugin(
    name = "BaseKtAdminUi",
    configurationPath = "basekt.adminUi",
    createConfiguration = ::BaseKtAdminUiConfig,
) {
    val problems = mutableListOf<String>()
    fun check(ok: Boolean, problem: () -> String) {
        if (!ok) problems += problem()
    }
    val path = "/" + pluginConfig.path.trim('/')
    val apiPath = "/" + pluginConfig.apiPath.trim('/')
    check(path != "/") { "BaseKtAdminUi's path must not be '/': the panel answers every path under it." }
    check(apiPath != path) { "BaseKtAdminUi's path and apiPath must differ, not both be '$path'." }
    val logo = pluginConfig.brand.logo?.trim()?.takeIf { it.isNotEmpty() }
    check(logo == null || logo.startsWith("/") || logo.startsWith("https://") || logo.startsWith("data:image/")) {
        "The brand logo must be a path on this server, an https: URL or a data:image/ URL, not '$logo'."
    }
    val login = loginOf(pluginConfig.login, problems)
    val provider = providerOf(pluginConfig.firebase, pluginConfig.oidc, pluginConfig.supabase, problems)
    check(login == null || provider == null) {
        "Sign in through the login route or through an identity provider, not both: set login.path or a provider."
    }
    val assetsRoot = pluginConfig.assetsRoot.trim().trim('/')
    check(assetsRoot.isNotEmpty() && ".." !in assetsRoot && assetsRoot.none { it <= ' ' || it == '\\' }) {
        "BaseKtAdminUi's assetsRoot must be a clean classpath directory name, not '${pluginConfig.assetsRoot}'."
    }
    val siteLink = pluginConfig.siteLink?.also { link ->
        check(link.label.isNotBlank() && link.label.length <= 40) { "The site link needs a label of 1 to 40 characters." }
        check(isSafeLinkUrl(link.url)) {
            "The site link must be a path starting with '/' or an http or https address, with no spaces, not '${link.url}'."
        }
    }
    require(problems.isEmpty()) { "BaseKtAdminUi's settings are not valid:\n" + problems.joinToString("\n") { "- $it" } }
    val shell = ShellConfig(
        path = path,
        apiPath = apiPath,
        filesPath = "/" + pluginConfig.filesPath.trim('/'),
        brand = ShellBrand(pluginConfig.brand.name, logo),
        defaultMode = pluginConfig.defaultMode,
        login = login,
        provider = provider,
        siteLink = siteLink,
    )
    val entry = PanelAssets.load(application.environment.classLoader, assetsRoot)
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
            staticResources("assets", "$assetsRoot/assets", index = null) {
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

/** The login route as the panel sees it, or `null` without one; adds what is wrong with it to [problems]. */
private fun loginOf(config: LoginConfig, problems: MutableList<String>): ShellLogin? {
    val loginPath = config.path?.trim()?.takeIf { it.isNotEmpty() }
    val refreshPath = config.refreshPath?.trim()?.takeIf { it.isNotEmpty() }
    if (loginPath == null) {
        if (refreshPath != null) problems += "The login's refreshPath needs a login path: the refresh token comes from the login route."
        return null
    }
    if (!isServerPath(loginPath)) {
        problems += "The login path must be a path on this server, such as /auth/login, not '$loginPath': passwords stay on this server."
    }
    if (refreshPath != null && !isServerPath(refreshPath)) {
        problems += "The login's refreshPath must be a path on this server, such as /auth/refresh, not '$refreshPath': refresh tokens stay on this server."
    }
    val fields = with(config) { listOf(usernameField, passwordField, tokenField, refreshTokenField, expiresInField) }
    if (fields.any { it.isBlank() }) problems += "The login's field names can't be blank."
    return with(config) {
        ShellLogin(loginPath, usernameField, usernameLabel, passwordField, tokenField, refreshPath, refreshTokenField, expiresInField)
    }
}

/** The identity provider as the panel sees it, or `null` without one; adds what is wrong with it to [problems]. */
private fun providerOf(
    firebase: FirebaseSignInConfig,
    oidc: OidcSignInConfig,
    supabase: SupabaseSignInConfig,
    problems: MutableList<String>,
): ShellProvider? {
    val apiKey = firebase.apiKey?.trim()?.takeIf { it.isNotEmpty() }
    val issuer = oidc.issuer?.trim()?.takeIf { it.isNotEmpty() }
    val supabaseUrl = supabase.url?.trim()?.takeIf { it.isNotEmpty() }
    val chosen = listOfNotNull(apiKey, issuer, supabaseUrl).size
    if (chosen > 1) {
        problems += "Choose one identity provider: Firebase (firebase.apiKey), OpenID Connect (oidc.issuer) or Supabase (supabase.url), not more than one."
    }
    if (apiKey != null) {
        val authDomain = firebase.authDomain?.trim().orEmpty()
        if (!hostName.matches(authDomain)) {
            problems += "Firebase needs its authDomain, a host name such as my-project.firebaseapp.com, not '$authDomain'."
        }
        if (firebase.providers.isEmpty()) problems += "Firebase needs at least one provider, such as password or google.com."
        firebase.providers.filterNot { firebaseProviderId.matches(it) }.forEach {
            problems += "'$it' is not a Firebase provider id: use one such as password, google.com, oidc.<name> or saml.<name>."
        }
        return ShellFirebase(apiKey, authDomain, firebase.projectId?.trim()?.takeIf { it.isNotEmpty() }, firebase.providers)
    }
    if (issuer != null) {
        if (!isIssuerUrl(issuer)) {
            problems += "The OIDC issuer must be an https URL (http only on localhost), not '$issuer'."
        }
        val clientId = oidc.clientId?.trim().orEmpty()
        if (clientId.isEmpty()) problems += "OpenID Connect needs the panel's clientId at the issuer."
        if ("openid" !in oidc.scopes) problems += "The OIDC scopes must include openid, for the ID token the panel sends."
        if (oidc.scopes.any { it.isBlank() || it.any(Char::isWhitespace) }) problems += "An OIDC scope can't be blank or hold spaces."
        if (oidc.label.isBlank() || oidc.label.length > 40) problems += "The OIDC label must be 1 to 40 characters."
        return ShellOidc(issuer.trimEnd('/'), clientId, oidc.scopes, oidc.label)
    }
    if (supabaseUrl != null) {
        if (!isIssuerUrl(supabaseUrl)) {
            problems += "The Supabase url must be an https URL (http only on localhost), not '$supabaseUrl'."
        }
        val anonKey = supabase.anonKey?.trim().orEmpty()
        if (anonKey.isEmpty()) problems += "Supabase needs its public anonKey."
        if (supabase.providers.isEmpty()) problems += "Supabase needs at least one provider, such as password, google or discord."
        supabase.providers.filterNot { supabaseProviderId.matches(it) }.forEach {
            problems += "'$it' is not a Supabase provider name: use password or a lowercase provider such as google, github or discord."
        }
        return ShellSupabase(supabaseUrl.trimEnd('/'), anonKey, supabase.providers)
    }
    return null
}

private fun isServerPath(path: String): Boolean =
    path.startsWith("/") && !path.startsWith("//") && path.none { it <= ' ' || it == '\\' }

private fun isIssuerUrl(url: String): Boolean {
    if (url.any { it <= ' ' } || '?' in url || '#' in url) return false
    if (url.startsWith("https://")) return url.length > "https://".length
    val host = url.removePrefix("http://").substringBefore('/').substringBefore(':')
    return url.startsWith("http://") && host in setOf("localhost", "127.0.0.1")
}

private val hostName = Regex("[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*(:[0-9]{1,5})?")
private val firebaseProviderId = Regex("password|[a-z0-9-]+(\\.[a-z0-9-]+)+")
private val supabaseProviderId = Regex("password|[a-z][a-z0-9_-]*")

private suspend fun RoutingContext.respondShell(config: ShellConfig, entry: PanelAssets.Entry) {
    call.response.header(HttpHeaders.CacheControl, "no-cache")
    call.respondShellPage(config, entry)
}

private const val ONE_YEAR = 365 * 24 * 60 * 60
