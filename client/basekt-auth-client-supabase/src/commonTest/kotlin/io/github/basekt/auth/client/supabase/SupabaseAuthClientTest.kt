package io.github.basekt.auth.client.supabase

import io.github.basekt.auth.client.AuthClientException
import io.github.basekt.auth.client.AuthFailureReason
import io.github.basekt.auth.client.AuthorizedCode
import io.github.basekt.auth.client.OAuthProviders
import io.github.basekt.auth.client.OAuthRedirectFlow
import io.github.basekt.auth.client.PkceChallenge
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SupabaseAuthClientTest {

    private val settings = SupabaseClientSettings(
        url = "https://demo.supabase.co/",
        anonKey = "anon-public-key",
        passwordResetRedirectUrl = "https://app.example.com/reset",
        supportedOAuthProviders = setOf("google", "discord"),
    )

    @Test
    fun `password signIn signUp refresh and recovery speak GoTrue REST contract`() = runTest {
        val seenPaths = mutableListOf<String>()
        val engine = MockEngine { request ->
            seenPaths += "${request.method.value} ${request.url.encodedPath}?${request.url.encodedQuery}"
            assertEquals("anon-public-key", request.headers["apikey"])
            when {
                request.url.encodedPath == "/auth/v1/token" && request.url.parameters["grant_type"] == "password" ->
                    respondJson(
                        """{"access_token":"jwt-1","refresh_token":"ref-1","expires_in":3600,"user":{"id":"u-1","email":"ana@example.com"}}""",
                    )
                request.url.encodedPath == "/auth/v1/signup" -> {
                    val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    if ("unconfirmed@" in body) {
                        respondJson("""{"user":{"id":"u-2","email":"unconfirmed@example.com"}}""")
                    } else {
                        respondJson(
                            """{"access_token":"jwt-2","refresh_token":"ref-2","expires_in":1800,"user":{"id":"u-3","email":"new@example.com"}}""",
                        )
                    }
                }
                request.url.encodedPath == "/auth/v1/token" && request.url.parameters["grant_type"] == "refresh_token" ->
                    respondJson(
                        """{"access_token":"jwt-3","refresh_token":"ref-3","expires_in":3600,"user":{"id":"u-1","email":"ana@example.com"}}""",
                    )
                request.url.encodedPath == "/auth/v1/recover" -> {
                    assertEquals("https://app.example.com/reset", request.url.parameters["redirect_to"])
                    respond("", HttpStatusCode.OK)
                }
                request.method == HttpMethod.Put && request.url.encodedPath == "/auth/v1/user" -> {
                    assertEquals("Bearer rec-access-jwt", request.headers[HttpHeaders.Authorization])
                    respondJson("""{"id":"u-1"}""")
                }
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val client = SupabaseAuthClient(settings, HttpClient(engine), nowEpochSeconds = { 10_000L })
        assertFalse("anon-public-key" in settings.toString())

        val signedIn = client.signIn(" ana@example.com ", "secret")
        assertEquals("ana@example.com", signedIn.userId)
        assertEquals("u-1", signedIn.subject)
        assertEquals("jwt-1", signedIn.accessToken)
        assertEquals(13_600L, signedIn.expiresAtEpochSeconds)

        val signedUp = client.signUp("new@example.com", "strong-pass")
        assertEquals("jwt-2", signedUp.accessToken)

        val unconfirmed = assertFailsWith<AuthClientException> {
            client.signUp("unconfirmed@example.com", "strong-pass")
        }
        assertEquals(AuthFailureReason.EmailConfirmationRequired, unconfirmed.reason)

        val refreshed = client.refresh("ana@example.com", "ref-1")
        assertEquals("jwt-3", refreshed.accessToken)

        client.sendPasswordReset("ana@example.com")
        client.setNewPassword("rec-access-jwt", "updated-pass")
        assertEquals(6, seenPaths.size)
    }

    @Test
    fun `OAuth PKCE builds S256 authorize URL and exchanges code_verifier on token endpoint`() = runTest {
        var capturedAuthorizeUrl: String? = null
        val fakeFlow = object : OAuthRedirectFlow {
            override suspend fun authorize(
                provider: String,
                rememberSession: Boolean,
                buildAuthorizeUrl: (redirectUri: String, challenge: PkceChallenge) -> String,
            ): AuthorizedCode {
                val challenge = PkceChallenge.generate()
                capturedAuthorizeUrl = buildAuthorizeUrl("http://127.0.0.1:54321/callback", challenge)
                return AuthorizedCode("pkce-auth-code", challenge.verifier)
            }
        }
        val engine = MockEngine { request ->
            assertEquals("/auth/v1/token", request.url.encodedPath)
            assertEquals("pkce", request.url.parameters["grant_type"])
            val json = Json.parseToJsonElement(
                (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString(),
            ).jsonObject
            assertEquals("pkce-auth-code", json["auth_code"]?.jsonPrimitive?.content)
            assertEquals(48, json["code_verifier"]?.jsonPrimitive?.content?.length)
            respondJson(
                """{"access_token":"oauth-jwt","refresh_token":"oauth-ref","expires_in":3600,"user":{"id":"u-oauth","email":"oauth@example.com"}}""",
            )
        }
        val client = SupabaseAuthClient(settings, HttpClient(engine), oauthFlow = fakeFlow, nowEpochSeconds = { 1_000L })
        assertTrue(client.supportsOAuth(OAuthProviders.GOOGLE))
        assertTrue(client.supportsOAuth(OAuthProviders.DISCORD))
        assertFalse(client.supportsOAuth(OAuthProviders.GITHUB))

        val session = client.signInWithOAuth(
            provider = OAuthProviders.GOOGLE,
            rememberSession = true,
            scopes = listOf("email", "profile"),
        )
        assertEquals("oauth@example.com", session.userId)
        assertEquals("u-oauth", session.subject)
        assertEquals("oauth-jwt", session.accessToken)
        val url = capturedAuthorizeUrl!!
        assertTrue("provider=google" in url)
        assertTrue("code_challenge_method=s256" in url)
        assertTrue("scopes=email+profile" in url || "scopes=email%20profile" in url)
    }

    @Test
    fun `classifyFailure maps GoTrue codes and statuses to typed AuthFailureReason`() {
        assertEquals(
            AuthFailureReason.InvalidCredentials,
            SupabaseAuthClient.classifyFailure(400, "invalid_credentials", "Invalid login credentials").reason,
        )
        assertEquals(
            AuthFailureReason.AccountAlreadyExists,
            SupabaseAuthClient.classifyFailure(422, "user_already_exists", "User already registered").reason,
        )
        assertEquals(
            AuthFailureReason.WeakPassword,
            SupabaseAuthClient.classifyFailure(422, "weak_password", "Password should be at least 8 characters").reason,
        )
        assertEquals(
            AuthFailureReason.RateLimited,
            SupabaseAuthClient.classifyFailure(429, "over_email_send_rate_limit", "Email rate limit exceeded").reason,
        )
        assertEquals(
            AuthFailureReason.SessionExpired,
            SupabaseAuthClient.classifyFailure(400, "refresh_token_not_found", "Invalid Refresh Token").reason,
        )
    }
}

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = body,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
)
