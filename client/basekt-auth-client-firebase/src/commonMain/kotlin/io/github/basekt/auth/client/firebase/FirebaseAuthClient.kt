package io.github.basekt.auth.client.firebase

import io.github.basekt.auth.client.AuthClient
import io.github.basekt.auth.client.AuthClientException
import io.github.basekt.auth.client.AuthFailureReason
import io.github.basekt.auth.client.AuthSession
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import io.ktor.http.parameters
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Multiplatform Firebase Auth [AuthClient] over Ktor [HttpClient] using Google's Identity Toolkit and Secure Token v1
 * REST endpoints directly (zero Firebase Android/iOS/JS SDK dependencies):
 * - `POST /accounts:signInWithPassword` for [signIn]
 * - `POST /accounts:signUp` for [signUp]
 * - `POST /v1/token` (`application/x-www-form-urlencoded`, browser-CORS-safe without preflight) for [refresh]
 * - `POST /accounts:sendOobCode` (`PASSWORD_RESET`) and `POST /accounts:update` for [sendPasswordReset] and [setNewPassword]
 *
 * ID tokens returned by this client verify directly against `server:basekt-auth-firebase`.
 */
public class FirebaseAuthClient(
    public val settings: FirebaseClientSettings,
    private val httpClient: HttpClient,
    private val nowEpochSeconds: () -> Long = { Clock.System.now().epochSeconds },
) : AuthClient {

    private val identityUrl: String = settings.normalizedIdentityBaseUrl
    private val tokenUrl: String = settings.normalizedSecureTokenUrl
    private val encodedKey: String = settings.apiKey.trim().encodeURLParameter()

    override val id: String
        get() = settings.id

    override suspend fun signIn(email: String, password: String): AuthSession =
        authenticateWithPassword("accounts:signInWithPassword", email, password)

    override suspend fun signUp(email: String, password: String): AuthSession =
        authenticateWithPassword("accounts:signUp", email, password)

    override suspend fun refresh(userId: String, refreshToken: String): AuthSession {
        val reply = execute {
            httpClient.submitForm(
                url = "$tokenUrl?key=$encodedKey",
                formParameters = parameters {
                    append("grant_type", "refresh_token")
                    append("refresh_token", refreshToken)
                },
            )
        }
        return parseSession(
            fallbackUserId = userId.trim(),
            reply = reply,
            idTokenField = "id_token",
            refreshTokenField = "refresh_token",
            expiresInField = "expires_in",
            subjectField = "user_id",
        )
    }

    override suspend fun sendPasswordReset(email: String) {
        val body = buildJsonObject {
            put("requestType", "PASSWORD_RESET")
            put("email", email.trim())
        }.toString()
        try {
            execute {
                httpClient.post("$identityUrl/accounts:sendOobCode?key=$encodedKey") {
                    setBody(TextContent(body, ContentType.Application.Json))
                }
            }
        } catch (refused: AuthClientException) {
            // Suppress missing/disabled account codes so reset requests never disclose whether an email is registered.
            if (refused.reason != AuthFailureReason.InvalidCredentials &&
                refused.reason != AuthFailureReason.AccountDisabled
            ) {
                throw refused
            }
        }
    }

    override suspend fun setNewPassword(accessToken: String, password: String) {
        val body = buildJsonObject {
            put("idToken", accessToken)
            put("password", password)
            put("returnSecureToken", true)
        }.toString()
        execute {
            httpClient.post("$identityUrl/accounts:update?key=$encodedKey") {
                setBody(TextContent(body, ContentType.Application.Json))
            }
        }
    }

    private suspend fun authenticateWithPassword(method: String, email: String, password: String): AuthSession {
        val normalizedEmail = email.trim()
        val body = buildJsonObject {
            put("email", normalizedEmail)
            put("password", password)
            put("returnSecureToken", true)
        }.toString()
        val reply = execute {
            httpClient.post("$identityUrl/$method?key=$encodedKey") {
                setBody(TextContent(body, ContentType.Application.Json))
            }
        }
        return parseSession(
            fallbackUserId = normalizedEmail,
            reply = reply,
            idTokenField = "idToken",
            refreshTokenField = "refreshToken",
            expiresInField = "expiresIn",
            subjectField = "localId",
        )
    }

    @Suppress("LongParameterList")
    private fun parseSession(
        fallbackUserId: String,
        reply: JsonObject,
        idTokenField: String,
        refreshTokenField: String,
        expiresInField: String,
        subjectField: String,
    ): AuthSession {
        fun requiredText(field: String): String =
            reply[field]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: throw AuthClientException(
                    AuthFailureReason.ProviderError,
                    "Firebase response lacked required field '$field'.",
                )
        val idToken = requiredText(idTokenField)
        val refreshToken = requiredText(refreshTokenField)
        val expiresIn = requiredText(expiresInField).toLongOrNull()
            ?: throw AuthClientException(
                AuthFailureReason.ProviderError,
                "Firebase response had non-numeric '$expiresInField'.",
            )
        val email = reply["email"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val subject = reply[subjectField]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val resolvedUserId = fallbackUserId.ifBlank { email ?: subject.orEmpty() }
        return AuthSession(
            userId = resolvedUserId,
            accessToken = idToken,
            refreshToken = refreshToken,
            expiresAtEpochSeconds = nowEpochSeconds() + expiresIn,
            email = email ?: fallbackUserId.takeIf { '@' in it },
            subject = subject,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun execute(request: suspend () -> HttpResponse): JsonObject {
        val response = try {
            request()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (transport: Throwable) {
            // Catch Throwable rather than Exception: browser fetch failures in Ktor Js/WasmJs surface as kotlin.Error.
            throw AuthClientException(
                AuthFailureReason.NetworkError,
                "Could not reach the authentication server.",
                cause = transport,
            )
        }
        val parsed = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
        if (response.status.value == HTTP_OK && parsed != null) return parsed
        val rawError = parsed?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty()
        throw classifyFailure(rawError)
    }

    /** Companion helpers for Firebase Identity Toolkit error mapping. */
    public companion object {
        private const val HTTP_OK: Int = 200

        /** Maps a raw Firebase error string (such as `"WEAK_PASSWORD : Password should be..."`) to [AuthClientException]. */
        public fun classifyFailure(rawError: String): AuthClientException {
            val code = rawError.substringBefore(" : ").trim()
            val detail = rawError.substringAfter(" : ", missingDelimiterValue = "").trim()
            val reason = when (code) {
                "EMAIL_NOT_FOUND", "INVALID_PASSWORD", "INVALID_LOGIN_CREDENTIALS" ->
                    AuthFailureReason.InvalidCredentials
                "EMAIL_EXISTS" -> AuthFailureReason.AccountAlreadyExists
                "WEAK_PASSWORD" -> AuthFailureReason.WeakPassword
                "INVALID_EMAIL", "MISSING_EMAIL" -> AuthFailureReason.InvalidEmail
                "MISSING_PASSWORD" -> AuthFailureReason.MissingPassword
                "USER_DISABLED" -> AuthFailureReason.AccountDisabled
                "TOO_MANY_ATTEMPTS_TRY_LATER", "RESET_PASSWORD_EXCEED_LIMIT" -> AuthFailureReason.RateLimited
                "TOKEN_EXPIRED", "INVALID_REFRESH_TOKEN", "USER_NOT_FOUND", "INVALID_ID_TOKEN",
                "CREDENTIAL_TOO_OLD_LOGIN_AGAIN",
                -> AuthFailureReason.SessionExpired
                else -> AuthFailureReason.ProviderError
            }
            val message = detail.ifBlank { defaultMessageFor(reason) }
            return AuthClientException(
                reason = reason,
                message = message,
                rawCode = code.ifBlank { null },
            )
        }

        private fun defaultMessageFor(reason: AuthFailureReason): String = when (reason) {
            AuthFailureReason.InvalidCredentials -> "Invalid email or password."
            AuthFailureReason.AccountAlreadyExists -> "An account with this email already exists."
            AuthFailureReason.WeakPassword -> "Password does not meet the minimum strength requirements."
            AuthFailureReason.InvalidEmail -> "Enter a valid email address."
            AuthFailureReason.MissingPassword -> "Enter a password."
            AuthFailureReason.AccountDisabled -> "This account has been disabled."
            AuthFailureReason.EmailConfirmationRequired -> "Confirm your email address before signing in."
            AuthFailureReason.RateLimited -> "Too many attempts; please wait before trying again."
            AuthFailureReason.SessionExpired -> "Your session has expired; please sign in again."
            AuthFailureReason.OAuthCancelled -> "Sign-in was cancelled."
            AuthFailureReason.OAuthUnsupported -> "OAuth sign-in is not supported."
            AuthFailureReason.NetworkError -> "Could not reach the authentication server."
            AuthFailureReason.ProviderError -> "Authentication failed."
        }
    }
}
