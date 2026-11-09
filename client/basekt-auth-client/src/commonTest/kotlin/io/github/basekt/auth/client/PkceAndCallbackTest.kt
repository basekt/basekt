package io.github.basekt.auth.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class PkceAndCallbackTest {

    @Test
    fun `PkceChallenge computes exact RFC 7636 Appendix B S256 vector and multi-block SHA-256`() {
        // RFC 7636 Appendix B exact verifier and expected S256 challenge.
        val rfcVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            PkceChallenge.challengeFor(rfcVerifier),
        )

        // Multi-block (>= 56 bytes) NIST FIPS 180-4 vector to exercise block boundary padding.
        val multiBlock = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
        val expectedHex = "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
        val actualHex = Sha256.digest(multiBlock.encodeToByteArray()).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        assertEquals(expectedHex, actualHex)

        val generated = PkceChallenge.generate()
        assertEquals(48, generated.verifier.length)
        assertEquals(PkceChallenge.challengeFor(generated.verifier), generated.challenge)
    }

    @Test
    fun `credential carriers redact secrets in toString`() {
        val session = AuthSession(
            userId = "ana@example.com",
            accessToken = "secret-access-jwt",
            refreshToken = "secret-refresh-token",
            expiresAtEpochSeconds = 1_800_000_000L,
        )
        val pkce = PkceChallenge("a".repeat(48), "challenge-xyz")
        val authorized = AuthorizedCode("secret-auth-code", "secret-verifier")
        val callbackCode = OAuthCallbackResult.Code("secret-callback-code")
        val pendingCode = PendingOAuthReturn.Code("secret-pending-code", "secret-pending-verifier", true, "google")
        val recovery = RecoveryLink.Token("secret-recovery-jwt")
        val saved = SavedSession("ana@example.com", refreshToken = "secret-saved-refresh")
        val storedPkce = StoredPkceState("secret-stored-verifier", rememberSession = true, provider = "discord")

        for (rendered in listOf(session, pkce, authorized, callbackCode, pendingCode, recovery, saved, storedPkce).map(Any::toString)) {
            assertFalse("secret-" in rendered, "toString leaked secret material: $rendered")
        }
    }

    @Test
    fun `OAuthCallbackParser parses query and fragment callbacks while ignoring recovery fragments`() {
        val codeResult = assertIs<OAuthCallbackResult.Code>(
            OAuthCallbackParser.parseCallback("https://app.example.com/callback?code=abc_123&state=xyz#ignored"),
        )
        assertEquals("abc_123", codeResult.code)

        assertEquals(
            OAuthCallbackResult.Cancelled,
            OAuthCallbackParser.parseCallback("?error=access_denied&error_description=User+cancelled"),
        )

        val expired = assertIs<OAuthCallbackResult.Failed>(
            OAuthCallbackParser.parseCallback("#error=access_denied&error_code=otp_expired&error_description=Link+expired"),
        )
        assertEquals("otp_expired", expired.errorCode)
        assertEquals("Link expired", expired.message)

        assertNull(OAuthCallbackParser.parseCallback("#access_token=rec-token&type=recovery"))
        assertNull(OAuthCallbackParser.parseCallback("?theme=dark&lang=en"))

        assertEquals(
            "?theme=dark&lang=en",
            OAuthCallbackParser.stripOAuthParams("?code=abc&theme=dark&state=123&error_description=none&lang=en"),
        )
        assertEquals("", OAuthCallbackParser.stripOAuthParams("?code=abc&state=123"))
    }

    @Test
    fun `RecoveryLinkParser extracts recovery tokens and expired notices`() {
        val active = assertIs<RecoveryLink.Token>(
            RecoveryLinkParser.parse("#access_token=recovery-jwt-123&type=recovery&expires_in=3600"),
        )
        assertEquals("recovery-jwt-123", active.accessToken)
        assertEquals(RecoveryLink.Expired, RecoveryLinkParser.parse("#error=access_denied&error_code=otp_expired"))
        assertNull(RecoveryLinkParser.parse("#tab=settings"))
    }

    @Test
    fun `DeepLinkOAuthFlow completes warm callbacks and records cold-start returns`() = runTest {
        val stateStore = InMemoryPkceStateStore()
        var openedUrl: String? = null
        val flow = DeepLinkOAuthFlow(
            callbackUri = "com.example.app://auth/callback",
            openUrl = { openedUrl = it },
            stateStore = stateStore,
        )

        val deferred = async(UnconfinedTestDispatcher(testScheduler)) {
            flow.authorize(OAuthProviders.GOOGLE, rememberSession = true) { redirectUri, challenge ->
                "https://project.supabase.co/auth/v1/authorize?redirect_to=$redirectUri&code_challenge=${challenge.challenge}"
            }
        }
        assertNotNull(openedUrl)
        assertNotNull(stateStore.load())

        assertFalse(flow.onCallbackUri("https://other.example.com/callback?code=wrong"))
        assertTrue(flow.onCallbackUri("com.example.app://auth/callback?code=warm-code-42"))

        val authorized = deferred.await()
        assertEquals("warm-code-42", authorized.code)
        assertNull(stateStore.load())
        assertNull(flow.takePendingReturn())

        // Simulate cold process recreation: stateStore still holds the PKCE verifier from before process death, and a
        // fresh DeepLinkOAuthFlow instance receives onCallbackUri before any UI subscribes.
        stateStore.save(StoredPkceState(verifier = "v".repeat(48), rememberSession = true, provider = "discord"))
        val coldFlow = DeepLinkOAuthFlow(
            callbackUri = "com.example.app://auth/callback",
            openUrl = {},
            stateStore = stateStore,
        )
        assertTrue(coldFlow.onCallbackUri("com.example.app://auth/callback?code=cold-code-99"))
        val pending = assertIs<PendingOAuthReturn.Code>(coldFlow.takePendingReturn())
        assertEquals("cold-code-99", pending.code)
        assertEquals("v".repeat(48), pending.codeVerifier)
        assertTrue(pending.rememberSession)
        assertEquals("discord", pending.provider)
        assertNull(coldFlow.takePendingReturn(), "takePendingReturn consumes the redirect once")
    }

    @Test
    fun `PageRedirectOAuthCoordinator persists PKCE state and consumes returning query or missing-state error`() = runTest {
        val map = mutableMapOf<String, String>()
        val storage = object : KeyValueStorage {
            override fun get(key: String): String? = map[key]
            override fun set(key: String, value: String) { map[key] = value }
            override fun remove(key: String) { map.remove(key) }
        }
        var navigatedTo: String? = null
        val coordinator = PageRedirectOAuthCoordinator(
            redirectUrl = { "https://app.example.com/login" },
            storage = storage,
            navigate = { navigatedTo = it },
        )

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.authorize(OAuthProviders.GOOGLE, rememberSession = true) { redirectUri, challenge ->
                "https://project.supabase.co/auth/v1/authorize?redirect_to=$redirectUri&code_challenge=${challenge.challenge}"
            }
        }
        assertNotNull(navigatedTo)
        job.cancel()

        val restored = assertIs<PendingOAuthReturn.Code>(coordinator.consumeCallback("?code=web-code-77"))
        assertEquals("web-code-77", restored.code)
        assertTrue(restored.rememberSession)
        assertTrue(map.isEmpty(), "consumeCallback wipes stored PKCE verifier immediately")

        // A replayed ?code=... URL without stored PKCE state fails cleanly with SessionExpired.
        val replayed = assertIs<PendingOAuthReturn.Failed>(coordinator.consumeCallback("?code=web-code-77"))
        assertEquals(AuthFailureReason.SessionExpired, replayed.reason)
    }
}
