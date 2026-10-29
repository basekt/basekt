package io.github.basekt.auth.supabase

import io.ktor.server.config.ApplicationConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The settings of Supabase Auth tokens: the Supabase project [url], the public [anonKey], optional [jwtSecret]
 * for local HMAC-SHA256 signature verification, how much [clockSkew] to allow, how long to cache user profiles
 * ([cacheTtl]), and which claims hold a caller's roles and record-scope attributes.
 */
public class SupabaseSettings(
    /** The Supabase project URL, e.g. `https://xyzcompany.supabase.co`. */
    public val url: String,
    /** The public anon key for contacting Supabase Auth (GoTrue). */
    public val anonKey: String,
    /** Optional JWT secret for verifying HMAC-SHA256 tokens locally without network calls. */
    public val jwtSecret: String? = null,
    /** The difference between this server's clock and Supabase's that the time checks allow. */
    public val clockSkew: Duration = 30.seconds,
    /** How long verified user profiles are cached before querying GoTrue again. */
    public val cacheTtl: Duration = 5.minutes,
    /** The custom claim that lists a caller's roles, as an array of strings or one string of names. */
    public val rolesClaim: String = "roles",
    /** The custom claims read as record-scope attributes, each named after its claim. */
    public val attributeClaims: List<String> = emptyList(),
    /** The realm named in `WWW-Authenticate` challenges. */
    public val realm: String = "supabase",
) {
    init {
        require(url.isNotBlank() && (url.startsWith("http://") || url.startsWith("https://"))) {
            "A Supabase URL must start with http:// or https://, not \"$url\"."
        }
        require(anonKey.isNotBlank()) { "Supabase anonKey must not be blank." }
        jwtSecret?.let {
            require(it.isNotBlank()) { "Supabase jwtSecret, when specified, must not be blank." }
        }
        require(clockSkew >= Duration.ZERO && clockSkew <= MAX_CLOCK_SKEW) {
            "The clock skew must be between 0 and $MAX_CLOCK_SKEW, not $clockSkew."
        }
        require(cacheTtl >= Duration.ZERO) {
            "The cache TTL cannot be negative, not $cacheTtl."
        }
        require(rolesClaim.isNotBlank()) { "The roles claim needs a name." }
        require(attributeClaims.none { it.isBlank() }) { "Attribute claims need names." }
    }

    /** The canonical `iss` claim of Supabase Auth tokens. */
    public val issuer: String get() = "${url.trimEnd('/')}/auth/v1"

    /** Returns true if [tokenIssuer] matches this project's URL or default Supabase issuer. */
    public fun matchesIssuer(tokenIssuer: String?): Boolean {
        if (tokenIssuer == null) return false
        val expected = issuer.trimEnd('/')
        val actual = tokenIssuer.trimEnd('/')
        return actual == expected || actual == url.trimEnd('/') || actual == "supabase"
    }

    override fun toString(): String =
        "SupabaseSettings(url=$url, clockSkew=$clockSkew, cacheTtl=$cacheTtl, rolesClaim=$rolesClaim, attributeClaims=$attributeClaims, realm=$realm)"

    public companion object {
        /** The most clock skew the settings accept. */
        public val MAX_CLOCK_SKEW: Duration = 5.minutes

        /**
         * Reads `url`, `anonKey`, and optional `jwtSecret`, `clockSkew`, `cacheTtl`, `rolesClaim`,
         * `attributeClaims` and `realm` from [config], such as the application's `auth.supabase` section.
         */
        public fun from(config: ApplicationConfig): SupabaseSettings = SupabaseSettings(
            url = config.property("url").getString(),
            anonKey = config.property("anonKey").getString(),
            jwtSecret = config.propertyOrNull("jwtSecret")?.getString(),
            clockSkew = config.propertyOrNull("clockSkew")?.getString()?.let(Duration::parseIsoString) ?: 30.seconds,
            cacheTtl = config.propertyOrNull("cacheTtl")?.getString()?.let(Duration::parseIsoString) ?: 5.minutes,
            rolesClaim = config.propertyOrNull("rolesClaim")?.getString() ?: "roles",
            attributeClaims = config.propertyOrNull("attributeClaims")?.getList() ?: emptyList(),
            realm = config.propertyOrNull("realm")?.getString() ?: "supabase",
        )
    }
}
