package io.github.basekt.auth.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

private class FakeAuthClient(
    override val id: String,
    override val supportedOAuthProviders: Set<String> = emptySet(),
    var onSignIn: suspend (String, String) -> AuthSession = { email, _ ->
        AuthSession(email, "token-$id", "refresh-$id", 1_000L, email = email)
    },
    var onRefresh: suspend (String, String) -> AuthSession = { userId, _ ->
        AuthSession(userId, "refreshed-$id", "refresh-$id-2", 2_000L, email = userId)
    },
    var onReset: suspend (String) -> Unit = {},
) : AuthClient, OAuthCapable {
    var refreshCount: Int = 0
    var resetEmails: MutableList<String> = mutableListOf()

    override suspend fun signIn(email: String, password: String): AuthSession = onSignIn(email, password)

    override suspend fun signUp(email: String, password: String): AuthSession =
        AuthSession(email, "signup-$id", "refresh-$id", 1_000L, email = email)

    override suspend fun refresh(userId: String, refreshToken: String): AuthSession {
        refreshCount++
        return onRefresh(userId, refreshToken)
    }

    override suspend fun sendPasswordReset(email: String) {
        resetEmails += email
        onReset(email)
    }

    override suspend fun setNewPassword(accessToken: String, password: String) = Unit

    override fun oauthAuthorizeUrl(
        provider: String,
        redirectUri: String,
        codeChallenge: String,
        scopes: List<String>,
    ): String = "https://$id.example.com/oauth/$provider?redirect_uri=$redirectUri"

    override suspend fun signInWithOAuth(
        provider: String,
        rememberSession: Boolean,
        scopes: List<String>,
    ): AuthSession = AuthSession("oauth@$id.example.com", "oauth-token-$id", "oauth-refresh-$id", 1_500L)

    override suspend fun completeOAuthSignIn(code: String, codeVerifier: String): AuthSession =
        AuthSession("callback@$id.example.com", "cb-token-$id", "cb-refresh-$id", 1_500L)
}

class SessionManagerAndCompositeTest {

    @Test
    fun `SessionManager remembers refresh tokens and refreshes expiring tokens once under concurrency`() = runTest {
        var now = 500L
        val store = InMemorySavedSessionStore()
        val client = FakeAuthClient(
            id = "primary",
            onSignIn = { email, _ -> AuthSession(email, "initial-jwt", "refresh-1", expiresAtEpochSeconds = 1_000L, email = email) },
            onRefresh = { userId, token ->
                assertEquals("refresh-1", token)
                AuthSession(userId, "rotated-jwt", "refresh-2", expiresAtEpochSeconds = 2_000L, email = userId)
            },
        )
        val manager = SessionManager(
            client = client,
            store = store,
            refreshMarginSeconds = 300L,
            nowEpochSeconds = { now },
        )

        val session = manager.signIn(" ana@example.com ", "correct-horse", remember = true)
        assertEquals("ana@example.com", session.userId)
        assertEquals("ana@example.com", store.load()?.userId)
        assertEquals("initial-jwt", manager.freshAccessToken())
        assertEquals(0, client.refreshCount)

        // Advance into the 5-minute refresh margin; concurrent callers share one single-flight refresh.
        now = 750L
        val tokens = (1..5).map { async { manager.freshAccessToken() } }.awaitAll()
        assertTrue(tokens.all { it == "rotated-jwt" })
        assertEquals(1, client.refreshCount)
        assertEquals("refresh-2", store.load()?.refreshToken)

        // Sign out of memory; saved refresh token still permits passwordless re-authentication on next launch.
        val relaunched = SessionManager(
            client = FakeAuthClient(
                id = "primary",
                onRefresh = { userId, token ->
                    assertEquals("refresh-2", token)
                    AuthSession(userId, "relaunch-jwt", "refresh-3", expiresAtEpochSeconds = 3_000L, email = userId)
                },
            ),
            store = store,
            nowEpochSeconds = { now },
        )
        assertTrue(relaunched.canSignInWithoutPassword("ana@example.com"))
        val resumed = relaunched.signIn("ana@example.com", password = "", remember = true)
        assertEquals("relaunch-jwt", resumed.accessToken)

        relaunched.forgetSavedSession()
        assertNull(store.load())
        relaunched.signOut()
        assertFalse(relaunched.canSignInWithoutPassword("ana@example.com"))
    }

    @Test
    fun `CompositeAuthClient falls back for migrating accounts and delegates OAuth`() = runTest {
        val supabase = FakeAuthClient(
            id = "supabase",
            supportedOAuthProviders = setOf(OAuthProviders.GOOGLE),
            onSignIn = { _, _ ->
                throw AuthClientException(AuthFailureReason.InvalidCredentials, "Invalid login credentials")
            },
            onRefresh = { _, _ ->
                throw AuthClientException(AuthFailureReason.SessionExpired, "Invalid refresh token")
            },
        )
        val firebase = FakeAuthClient(
            id = "firebase",
            onSignIn = { email, _ -> AuthSession(email, "firebase-jwt", "fb-refresh", 1_200L, email = email) },
            onRefresh = { userId, _ -> AuthSession(userId, "firebase-refreshed", "fb-refresh-2", 2_400L, email = userId) },
            onReset = { throw AuthClientException(AuthFailureReason.RateLimited, "Fallback throttled") },
        )
        val composite = CompositeAuthClient(primary = supabase, fallback = firebase)

        val migratedSignIn = composite.signIn("legacy@example.com", "secret")
        assertEquals("firebase-jwt", migratedSignIn.accessToken)

        val migratedRefresh = composite.refresh("legacy@example.com", "fb-refresh")
        assertEquals("firebase-refreshed", migratedRefresh.accessToken)

        // Password reset notifies both providers and swallows fallback errors once primary succeeds.
        composite.sendPasswordReset("legacy@example.com")
        assertEquals(listOf("legacy@example.com"), supabase.resetEmails)
        assertEquals(listOf("legacy@example.com"), firebase.resetEmails)

        // OAuth routes to primary (Supabase) and refuses unconfigured providers cleanly.
        assertTrue(composite.supportsOAuth(OAuthProviders.GOOGLE))
        assertFalse(composite.supportsOAuth(OAuthProviders.APPLE))
        assertEquals("oauth-token-supabase", composite.signInWithOAuth(OAuthProviders.GOOGLE).accessToken)
        val unsupported = assertFailsWith<AuthClientException> {
            composite.signInWithOAuth(OAuthProviders.APPLE)
        }
        assertEquals(AuthFailureReason.OAuthUnsupported, unsupported.reason)
    }
}
