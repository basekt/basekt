package io.github.basekt.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.github.basekt.server.Identity
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.bearer
import io.ktor.server.auth.jwt.JWTAuthenticationProvider
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.config.ApplicationConfig
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * The settings of HMAC-signed JWTs (HS256): who issues them, who they're for, and the shared secret.
 *
 * Keep the secret out of source control: put it in the application's configuration from a secret store or an
 * environment variable, for example `secret = ${?JWT_SECRET}` in HOCON.
 */
public class JwtSettings(
    /** The `iss` claim tokens must carry. */
    public val issuer: String,
    /** The `aud` claim tokens must carry. */
    public val audience: String,
    /** The HMAC secret that signs tokens. */
    private val secret: String,
    /** The realm named in `WWW-Authenticate` challenges. */
    public val realm: String = "basekt",
    /** The claim that lists a caller's roles. */
    public val rolesClaim: String = "roles",
) {
    init {
        require(secret.length >= 32) { "A JWT secret needs at least 32 characters." }
    }

    internal val algorithm: Algorithm = Algorithm.HMAC256(secret)

    /**
     * Issues a token for [identity], valid for [validFor] from [now]. Each of its attributes becomes an array claim of
     * the same name, which `PrincipalRoles.jwt(attributeClaims = ...)` reads back. For login routes and tests; BaseKt
     * itself never issues tokens.
     */
    public fun issue(identity: Identity, validFor: Duration, now: Instant = Clock.System.now()): String {
        val reserved = identity.attributes.keys.filter { it in RESERVED_CLAIMS || it == rolesClaim }
        require(reserved.isEmpty()) { "Attributes can't use the reserved claim names ${reserved.joinToString()}." }
        val builder = JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(identity.id)
            .withClaim(rolesClaim, identity.roles.toList())
            .withIssuedAt(now.toJavaInstant())
            .withExpiresAt((now + validFor).toJavaInstant())
        for ((name, values) in identity.attributes) builder.withClaim(name, values.toList())
        return builder.sign(algorithm)
    }

    override fun toString(): String = "JwtSettings(issuer=$issuer, audience=$audience, realm=$realm, rolesClaim=$rolesClaim)"

    /** Reads settings from configuration. */
    public companion object {
        /**
         * Reads `issuer`, `audience` and `secret`, and the optional `realm` and `rolesClaim`, from [config], such as
         * the application's `auth.jwt` section.
         */
        public fun from(config: ApplicationConfig): JwtSettings = JwtSettings(
            issuer = config.property("issuer").getString(),
            audience = config.property("audience").getString(),
            secret = config.property("secret").getString(),
            realm = config.propertyOrNull("realm")?.getString() ?: "basekt",
            rolesClaim = config.propertyOrNull("rolesClaim")?.getString() ?: "roles",
        )
    }
}

/** The registered JWT claims that an [Identity]'s attributes can't replace. */
private val RESERVED_CLAIMS = setOf("iss", "sub", "aud", "exp", "nbf", "iat", "jti")

/**
 * Registers a Ktor JWT provider called [name] for tokens signed with [settings]: it checks the signature, issuer,
 * audience and expiry, and accepts tokens with a subject. [PrincipalRoles.Default] maps its [JWTPrincipal]s with the
 * default `roles` claim; with another [JwtSettings.rolesClaim], pass `PrincipalRoles.jwt(rolesClaim = ...)` to
 * [AuthIdentities]. [configure] can change the provider further.
 */
public fun AuthenticationConfig.baseKtJwt(
    name: String?,
    settings: JwtSettings,
    configure: JWTAuthenticationProvider.Config.() -> Unit = {},
) {
    jwt(name) {
        realm = settings.realm
        verifier(JWT.require(settings.algorithm).withIssuer(settings.issuer).withAudience(settings.audience).build())
        validate { credential ->
            JWTPrincipal(credential.payload).takeIf { !credential.payload.subject.isNullOrBlank() }
        }
        configure()
    }
}

/**
 * Registers a Ktor bearer provider called [name] that resolves an opaque token, such as an API key, to an
 * [Identity] with [resolve]. A `null` identity rejects the token.
 */
public fun AuthenticationConfig.bearerIdentities(name: String?, resolve: suspend (token: String) -> Identity?) {
    bearer(name) {
        authenticate { credential -> resolve(credential.token) }
    }
}
