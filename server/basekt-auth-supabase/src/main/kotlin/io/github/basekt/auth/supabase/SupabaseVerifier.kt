package io.github.basekt.auth.supabase

import com.auth0.jwt.interfaces.DecodedJWT
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toJavaDuration
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinDuration

/**
 * Validates Supabase access tokens via [fetcher] and caches verified profiles locally.
 */
public class SupabaseVerifier(
    private val settings: SupabaseSettings,
    private val fetcher: SupabaseUserFetcher,
    private val clock: Clock = Clock.System,
) {
    private val cache = ConcurrentHashMap<String, CachedUser>()

    private data class CachedUser(val user: SupabaseUser, val expiresAt: Instant)

    /**
     * Verifies [token] against the cache or [fetcher].
     * Returns null if expired, rejected by GoTrue, or unreachable.
     */
    public suspend fun verify(token: String, unverified: DecodedJWT): SupabaseUser? {
        val now = clock.now()
        val cached = cache[token]
        if (cached != null && cached.expiresAt > now) {
            return cached.user
        }

        // Fast expiration check on the token claim
        val exp = unverified.expiresAtAsInstant
        if (exp != null && exp.plus(settings.clockSkew.toJavaDuration()).isBefore(now.toJavaInstant())) {
            return null
        }

        val user = fetcher.fetch(token) ?: return null

        val tokenRemaining = exp?.let { java.time.Duration.between(now.toJavaInstant(), it) }
        val ttl = if (tokenRemaining != null && !tokenRemaining.isNegative) {
            minOf(settings.cacheTtl, tokenRemaining.toKotlinDuration())
        } else {
            settings.cacheTtl
        }
        cache[token] = CachedUser(user, now + ttl)
        return user
    }
}
