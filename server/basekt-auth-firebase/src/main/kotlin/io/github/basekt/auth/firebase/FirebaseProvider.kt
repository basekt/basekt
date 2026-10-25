package io.github.basekt.auth.firebase

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTDecodeException
import io.github.basekt.auth.AuthIdentities
import io.github.basekt.auth.PrincipalRoles
import io.github.basekt.server.Identity
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.jwt.JWTAuthenticationProvider
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import kotlin.time.Clock
import kotlin.time.toJavaInstant

/** The longest uid Firebase gives a user. */
private const val MAX_UID_LENGTH = 128

/**
 * Registers a Ktor JWT provider called [name] for Firebase ID tokens of the project in [settings], so that users who
 * sign in with Firebase Authentication are callers of BaseKt:
 *
 * ```kotlin
 * install(Authentication) {
 *     firebase("firebase", FirebaseSettings.from(environment.config.config("auth.firebase")))
 *     baseKtJwt("jwt", JwtSettings.from(environment.config.config("auth.jwt")))   // machine tokens, say
 * }
 * install(BaseKt) {
 *     identities = AuthIdentities("firebase", "jwt")   // a caller with either token is identified
 * }
 * ```
 *
 * The provider checks the token's signature with [keys], and its issuer, audience and times against [settings] (see
 * [FirebaseSettings]). A token that isn't a Firebase token of this project, such as another provider's JWT, is
 * refused before any key is fetched, so the next provider gets its turn. An accepted token becomes an [Identity]
 * through [roles], which by default reads the uid (`sub`) as the id, [FirebaseSettings.rolesClaim] as the roles and
 * [FirebaseSettings.attributeClaims] as attributes, all from custom claims set with the Admin SDK's
 * `setCustomUserClaims`. The principal is that [Identity] itself, which [PrincipalRoles.Default] passes on unchanged,
 * so [AuthIdentities] can mix it with other providers whose principals map differently.
 *
 * @param keys the cache of Google's public keys; pass one with a test [FirebaseKeyFetcher] in tests.
 * @param roles how a verified token, as a [JWTPrincipal], becomes an identity; `null` refuses the token.
 * @param configure changes the underlying JWT provider further.
 */
public fun AuthenticationConfig.firebase(
    name: String?,
    settings: FirebaseSettings,
    keys: FirebaseKeys = FirebaseKeys(),
    roles: PrincipalRoles = PrincipalRoles.jwt(rolesClaim = settings.rolesClaim, attributeClaims = settings.attributeClaims),
    configure: JWTAuthenticationProvider.Config.() -> Unit = {},
) {
    jwt(name) {
        realm = settings.realm
        verifier { header -> firebaseVerifier(header, settings, keys) }
        validate { credential ->
            val uid = credential.payload.subject
            if (uid.isNullOrBlank() || uid.length > MAX_UID_LENGTH) null else roles.identityOf(JWTPrincipal(credential.payload))
        }
        configure()
    }
}

/**
 * Returns a verifier for the bearer token in [header] if it claims to be an ID token of the project, signed with a
 * key Google publishes, or `null`, which refuses it as invalid.
 */
private suspend fun firebaseVerifier(header: HttpAuthHeader, settings: FirebaseSettings, keys: FirebaseKeys): JWTVerifier? {
    val token = (header as? HttpAuthHeader.Single)?.takeIf { it.authScheme.equals("Bearer", ignoreCase = true) }?.blob ?: return null
    val unverified = try {
        JWT.decode(token)
    } catch (_: JWTDecodeException) {
        return null
    }
    // Only now, with a token that says it's this project's, may keys be fetched.
    if (unverified.algorithm != "RS256" || unverified.issuer != settings.issuer) return null
    val key = keys.key(unverified.keyId ?: return null) ?: return null
    val skew = settings.clockSkew
    return JWT.require(Algorithm.RSA256(key, null))
        .withIssuer(settings.issuer)
        .withAudience(settings.projectId)
        .withClaimPresence("exp")
        .withClaimPresence("iat")
        .withClaim("auth_time") { claim, _ ->
            val authTime = claim.asInstant()
            authTime != null && authTime <= (Clock.System.now() + skew).toJavaInstant()
        }
        .acceptLeeway(skew.inWholeSeconds)
        .build()
}
