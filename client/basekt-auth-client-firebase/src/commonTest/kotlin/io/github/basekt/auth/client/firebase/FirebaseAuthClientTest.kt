package io.github.basekt.auth.client.firebase

import io.github.basekt.auth.client.AuthClientException
import io.github.basekt.auth.client.AuthFailureReason
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

class FirebaseAuthClientTest {

    private val settings = FirebaseClientSettings(apiKey = "firebase-web-api-key")

    @Test
    fun `signIn signUp form refresh and non-enumerating password reset speak Identity Toolkit contract`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("firebase-web-api-key", request.url.parameters["key"])
            when (request.url.encodedPath) {
                "/v1/accounts:signInWithPassword" ->
                    respondJson(
                        """{"localId":"fb-u1","email":"ana@example.com","idToken":"fb-id-1","refreshToken":"fb-ref-1","expiresIn":"3600"}""",
                    )
                "/v1/accounts:signUp" ->
                    respondJson(
                        """{"localId":"fb-u2","email":"new@example.com","idToken":"fb-id-2","refreshToken":"fb-ref-2","expiresIn":"3600"}""",
                    )
                "/v1/token" -> {
                    assertEquals(
                        ContentType.Application.FormUrlEncoded,
                        request.body.contentType?.withoutParameters(),
                    )
                    respondJson(
                        """{"user_id":"fb-u1","id_token":"fb-id-3","refresh_token":"fb-ref-3","expires_in":"3600"}""",
                    )
                }
                "/v1/accounts:sendOobCode" -> {
                    val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    when {
                        "missing@" in body -> respondJson(
                            """{"error":{"message":"EMAIL_NOT_FOUND"}}""",
                            HttpStatusCode.BadRequest,
                        )
                        "throttled@" in body -> respondJson(
                            """{"error":{"message":"TOO_MANY_ATTEMPTS_TRY_LATER"}}""",
                            HttpStatusCode.BadRequest,
                        )
                        else -> respondJson("""{"email":"ana@example.com"}""")
                    }
                }
                "/v1/accounts:update" ->
                    respondJson("""{"localId":"fb-u1","email":"ana@example.com"}""")
                else -> error("Unexpected Firebase path: ${request.url.encodedPath}")
            }
        }

        val client = FirebaseAuthClient(settings, HttpClient(engine), nowEpochSeconds = { 5_000L })
        assertFalse("firebase-web-api-key" in settings.toString())

        val signedIn = client.signIn(" ana@example.com ", "secret")
        assertEquals("ana@example.com", signedIn.userId)
        assertEquals("fb-u1", signedIn.subject)
        assertEquals("fb-id-1", signedIn.accessToken)
        assertEquals(8_600L, signedIn.expiresAtEpochSeconds)

        val signedUp = client.signUp("new@example.com", "secret")
        assertEquals("fb-id-2", signedUp.accessToken)

        val refreshed = client.refresh("ana@example.com", "fb-ref-1")
        assertEquals("fb-id-3", refreshed.accessToken)
        assertEquals("fb-u1", refreshed.subject)

        // EMAIL_NOT_FOUND is swallowed on password reset to prevent account enumeration; rate limits propagate.
        client.sendPasswordReset("missing@example.com")
        val throttled = assertFailsWith<AuthClientException> {
            client.sendPasswordReset("throttled@example.com")
        }
        assertEquals(AuthFailureReason.RateLimited, throttled.reason)

        client.setNewPassword("fb-id-3", "new-password")
    }

    @Test
    fun `browser Error during fetch maps to NetworkError and Firebase codes map to AuthFailureReason`() = runTest {
        val failingClient = FirebaseAuthClient(
            settings,
            HttpClient(MockEngine { throw Error("Fail to fetch") }),
        )
        val network = assertFailsWith<AuthClientException> {
            failingClient.signIn("ana@example.com", "secret")
        }
        assertEquals(AuthFailureReason.NetworkError, network.reason)

        assertEquals(
            AuthFailureReason.WeakPassword,
            FirebaseAuthClient.classifyFailure("WEAK_PASSWORD : Password should be at least 6 characters").reason,
        )
        assertEquals(
            AuthFailureReason.AccountAlreadyExists,
            FirebaseAuthClient.classifyFailure("EMAIL_EXISTS").reason,
        )
        assertEquals(
            AuthFailureReason.AccountDisabled,
            FirebaseAuthClient.classifyFailure("USER_DISABLED").reason,
        )
        assertEquals(
            AuthFailureReason.SessionExpired,
            FirebaseAuthClient.classifyFailure("INVALID_REFRESH_TOKEN").reason,
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
