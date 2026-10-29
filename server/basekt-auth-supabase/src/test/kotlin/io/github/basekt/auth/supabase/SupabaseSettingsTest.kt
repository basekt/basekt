package io.github.basekt.auth.supabase

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SupabaseSettingsTest {
    @Test
    fun parsesSettingsFromConfig() {
        val config = MapApplicationConfig(
            "url" to "https://xyz.supabase.co",
            "anonKey" to "anon-secret-key-123",
            "jwtSecret" to "jwt-secret-xyz",
            "clockSkew" to "PT15S",
            "cacheTtl" to "PT10M",
            "rolesClaim" to "custom_roles",
            "realm" to "custom-realm",
        )
        val settings = SupabaseSettings.from(config)
        assertEquals("https://xyz.supabase.co", settings.url)
        assertEquals("anon-secret-key-123", settings.anonKey)
        assertEquals("jwt-secret-xyz", settings.jwtSecret)
        assertEquals(15.seconds, settings.clockSkew)
        assertEquals(10.minutes, settings.cacheTtl)
        assertEquals("custom_roles", settings.rolesClaim)
        assertEquals("custom-realm", settings.realm)
        assertEquals("https://xyz.supabase.co/auth/v1", settings.issuer)
    }

    @Test
    fun defaultSettingsApply() {
        val settings = SupabaseSettings(
            url = "https://myproject.supabase.co",
            anonKey = "anon-key",
        )
        assertEquals(30.seconds, settings.clockSkew)
        assertEquals(5.minutes, settings.cacheTtl)
        assertEquals("roles", settings.rolesClaim)
        assertEquals("supabase", settings.realm)
        assertNull(settings.jwtSecret)
    }

    @Test
    fun validatesIssuer() {
        val settings = SupabaseSettings(
            url = "https://myproject.supabase.co",
            anonKey = "anon-key",
        )
        assertTrue(settings.matchesIssuer("https://myproject.supabase.co/auth/v1"))
        assertTrue(settings.matchesIssuer("https://myproject.supabase.co"))
        assertTrue(settings.matchesIssuer("supabase"))
        assertFalse(settings.matchesIssuer("https://otherproject.supabase.co"))
        assertFalse(settings.matchesIssuer("https://securetoken.google.com/proj"))
        assertFalse(settings.matchesIssuer(null))
    }

    @Test
    fun masksSecretsInToString() {
        val settings = SupabaseSettings(
            url = "https://myproject.supabase.co",
            anonKey = "super-secret-anon-key",
            jwtSecret = "super-secret-jwt-key",
        )
        val string = settings.toString()
        assertTrue("https://myproject.supabase.co" in string)
        assertFalse("super-secret-anon-key" in string)
        assertFalse("super-secret-jwt-key" in string)
    }

    @Test
    fun rejectsInvalidUrl() {
        assertFailsWith<IllegalArgumentException> {
            SupabaseSettings(url = "not-a-url", anonKey = "key")
        }
        assertFailsWith<IllegalArgumentException> {
            SupabaseSettings(url = "   ", anonKey = "key")
        }
        assertFailsWith<IllegalArgumentException> {
            SupabaseSettings(url = "https://valid.supabase.co", anonKey = "")
        }
    }
}
