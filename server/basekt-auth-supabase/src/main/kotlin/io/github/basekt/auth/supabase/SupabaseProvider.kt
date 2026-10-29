package io.github.basekt.auth.supabase

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTDecodeException
import io.github.basekt.auth.PrincipalRoles
import io.github.basekt.server.Identity
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.BearerAuthenticationProvider
import io.ktor.server.auth.bearer
import io.ktor.server.auth.jwt.JWTPrincipal

private const val MAX_UID_LENGTH = 128

/**
 * Registers a Ktor Bearer provider called [name] for Supabase Auth access tokens.
 *
 * Checks:
 * - If [SupabaseSettings.jwtSecret] is set, verifies HMAC-SHA256 signature locally.
 * - Otherwise, verifies via GoTrue endpoint (`/auth/v1/user`) with in-memory caching.
 * - Ignores tokens whose issuer does not match Supabase, allowing other providers (like Firebase) to proceed.
 * - Maps user id (`sub`), roles, and custom claims to BaseKt [Identity].
 */
public fun AuthenticationConfig.supabase(
    name: String?,
    settings: SupabaseSettings,
    fetcher: SupabaseUserFetcher = SupabaseUserFetcher.gotrue(
        settings.url,
        settings.anonKey,
        settings.rolesClaim,
        settings.attributeClaims,
    ),
    roles: PrincipalRoles = PrincipalRoles.jwt(rolesClaim = settings.rolesClaim, attributeClaims = settings.attributeClaims),
    configure: BearerAuthenticationProvider.Config.() -> Unit = {},
) {
    val verifier = SupabaseVerifier(settings, fetcher)
    bearer(name) {
        realm = settings.realm
        authenticate { credential ->
            val token = credential.token
            val unverified = try {
                JWT.decode(token)
            } catch (_: JWTDecodeException) {
                return@authenticate null
            }

            if (!settings.matchesIssuer(unverified.issuer)) return@authenticate null

            if (settings.jwtSecret != null) {
                val jwtVerifier = JWT.require(Algorithm.HMAC256(settings.jwtSecret))
                    .withClaimPresence("sub")
                    .acceptLeeway(settings.clockSkew.inWholeSeconds)
                    .build()
                val verified = try {
                    jwtVerifier.verify(token)
                } catch (_: Exception) {
                    return@authenticate null
                }
                val uid = verified.subject?.takeIf { it.isNotBlank() && it.length <= MAX_UID_LENGTH } ?: return@authenticate null
                return@authenticate roles.identityOf(JWTPrincipal(verified)) ?: Identity(uid)
            }

            val user = verifier.verify(token, unverified) ?: return@authenticate null
            if (user.id.isBlank() || user.id.length > MAX_UID_LENGTH) return@authenticate null

            val mapped = roles.identityOf(JWTPrincipal(unverified))
            if (mapped != null) {
                val combinedRoles = mapped.roles + user.roles
                val allAttributeKeys = mapped.attributes.keys + user.attributes.keys
                val combinedAttributes = allAttributeKeys.associateWith { key ->
                    mapped.attributes[key].orEmpty() + user.attributes[key].orEmpty()
                }
                Identity(user.id, combinedRoles, combinedAttributes)
            } else {
                Identity(user.id, user.roles, user.attributes)
            }
        }
        configure()
    }
}
