package io.github.basekt.auth.firebase

import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private class FakeClock(var now: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock {
    override fun now(): Instant = now
}

private fun newKey(): RSAPublicKey =
    KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey

class FirebaseKeysTest {
    private val first = newKey()
    private val second = newKey()

    @Test
    fun `keys are kept for their max age, then fetched again`() = runTest {
        val clock = FakeClock()
        var fetches = 0
        val keys = FirebaseKeys({ fetches++; FirebaseKeySet(mapOf("a" to if (fetches == 1) first else second), 2.hours) }, clock = clock)
        assertSame(first, keys.key("a"))
        clock.now += 1.hours
        assertSame(first, keys.key("a"))
        assertEquals(1, fetches)
        clock.now += 1.hours
        assertSame(second, keys.key("a"))
        assertEquals(2, fetches)
    }

    @Test
    fun `a short max age is held for the minimum refresh interval`() = runTest {
        val clock = FakeClock()
        var fetches = 0
        val keys = FirebaseKeys({ fetches++; FirebaseKeySet(mapOf("a" to first), Duration.ZERO) }, minRefreshInterval = 1.minutes, clock = clock)
        repeat(5) { keys.key("a") }
        assertEquals(1, fetches)
        clock.now += 1.minutes
        keys.key("a")
        assertEquals(2, fetches)
    }

    @Test
    fun `an unknown key id fetches again at most once per interval`() = runTest {
        val clock = FakeClock()
        var fetches = 0
        val keys = FirebaseKeys(
            { fetches++; FirebaseKeySet(if (fetches == 1) mapOf("a" to first) else mapOf("a" to first, "b" to second), 6.hours) },
            minRefreshInterval = 1.minutes,
            clock = clock,
        )
        assertSame(first, keys.key("a"))
        assertNull(keys.key("b"))
        assertNull(keys.key("zzz"))
        assertEquals(1, fetches)
        clock.now += 61.seconds
        assertSame(second, keys.key("b"))
        assertEquals(2, fetches)
    }

    @Test
    fun `a failed refresh keeps the old keys, and with none the error reaches the caller`() = runTest {
        val clock = FakeClock()
        var fail = false
        val keys = FirebaseKeys({ if (fail) throw IOException("down") else FirebaseKeySet(mapOf("a" to first), 1.hours) }, clock = clock)
        assertSame(first, keys.key("a"))
        fail = true
        clock.now += 2.hours
        assertSame(first, keys.key("a"))

        assertFailsWith<IOException> { FirebaseKeys({ throw IOException("down") }, clock = clock).key("a") }
    }

    @Test
    fun `nothing is fetched when the cache is made`() {
        FirebaseKeys({ error("fetched too early") })
        FirebaseKeyFetcher.google()
    }

    @Test
    fun `max age is read from Cache-Control`() {
        assertEquals(19_258.seconds, maxAgeOf("public, max-age=19258, must-revalidate, no-transform"))
        assertEquals(60.seconds, maxAgeOf("MAX-AGE=60"))
        assertEquals(Duration.ZERO, maxAgeOf("no-cache, max-age=60"))
        assertEquals(Duration.ZERO, maxAgeOf("public"))
        assertEquals(Duration.ZERO, maxAgeOf("max-age=soon"))
        assertEquals(Duration.ZERO, maxAgeOf(null))
    }

    @Test
    fun `settings come from configuration and are validated`() {
        val config = MapApplicationConfig(
            "projectId" to "demo-project",
            "clockSkew" to "PT10S",
            "rolesClaim" to "groups",
            "realm" to "people",
        ).apply { put("attributeClaims", listOf("site", "team")) }
        val settings = FirebaseSettings.from(config)
        assertEquals("https://securetoken.google.com/demo-project", settings.issuer)
        assertEquals(10.seconds, settings.clockSkew)
        assertEquals("groups", settings.rolesClaim)
        assertEquals(listOf("site", "team"), settings.attributeClaims)
        assertEquals("people", settings.realm)
        assertEquals(30.seconds, FirebaseSettings.from(MapApplicationConfig("projectId" to "p")).clockSkew)

        assertFailsWith<IllegalArgumentException> { FirebaseSettings("") }
        assertFailsWith<IllegalArgumentException> { FirebaseSettings("a/b") }
        assertFailsWith<IllegalArgumentException> { FirebaseSettings("p", clockSkew = 1.hours) }
        assertFailsWith<IllegalArgumentException> { FirebaseSettings("p", clockSkew = (-1).seconds) }
    }
}
