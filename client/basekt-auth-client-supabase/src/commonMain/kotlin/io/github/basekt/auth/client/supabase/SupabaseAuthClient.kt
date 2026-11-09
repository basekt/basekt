package io.github.basekt.auth.client.supabase

import io.github.basekt.auth.client.AuthClient
import io.github.basekt.auth.client.AuthClientException
import io.github.basekt.auth.client.AuthFailureReason
import io.github.basekt.auth.client.AuthSession
import io.github.basekt.auth.client.OAuthCapable
import io.github.basekt.auth.client.OAuthRedirectFlow
import io.github.basekt.auth.client.UnsupportedOAuthRedirectFlow
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Multiplatform Supabase Auth (GoTrue) [AuthClient] and [OAuthCapable] implementation over Ktor [HttpClient].
 *
 * Speaks GoTrue's REST endpoints directly with zero platform SDK dependencies:
 * - `POST /auth/v1/token?grant_type=password` for [signIn]
 * - `POST /auth/v1/signup` for [signUp]
 * - `POST /auth/v1/token?grant_type=refresh_token` for [refresh]
 * - `POST /auth/v1/recover` and `PUT /auth/v1/user` for [sendPasswordReset] and [setNewPassword]
 * - `GET /auth/v1/authorize` + `POST /auth/v1/token?grant_type=pkce` for [signInWithOAuth] and [completeOAuthSignIn]
 *
 * Access tokens returned by this client verify directly against `server:basekt-auth-supabase`.
 */
public class SupabaseAuthClient(
    public val settings: SupabaseClientSettings,
    private val httpClient: HttpClient,
    private val oauthFlow: OAuthRedirectFlow = UnsupportedOAuthRedirectFlow,
    private val redirectUrlProvider: () -> String? = { settings.passwordResetRedirectUrl },
    private val nowEpochSeconds: () -> Long = { Clock.System.now().epochSeconds },
) : AuthClient, OAuthCapable {

    private val authUrl: String = settings.authBaseUrl

    override val id: String
        get() = settings.id

    override val supportedOAuthProviders: Set<String>
        get() = if (oauthFlow.isSupported) settings.normalizedOAuthProviders else emptySet()

    override fun oauthAuthorizeUrl(
        provider: String,
        redirectUri: String,
        codeChallenge: String,
        scopes: List<String>,
    ): String {
        val normalizedProvider = provider.trim().lowercase()
        require(normalizedProvider.isNotEmpty()) { "OAuth provider must not be blank." }
        require(redirectUri.isNotBlank()) { "OAuth redirectUri must not be blank." }
        require(codeChallenge.isNotBlank()) { "OAuth codeChallenge must not be blank." }
        return buildString {
            append(authUrl)
            append("/authorize?provider=").append(normalizedProvider.encodeURLParameter())
            append("&redirect_to=").append(redirectUri.encodeURLParameter())
            append("&code_challenge=").append(codeChallenge.encodeURLParameter())
            append("&code_challenge_method=s256")
            val scopeParam = scopes.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
            if (scopeParam.isNotEmpty()) {
                append("&scopes=").append(scopeParam.encodeURLParameter())
            }
        }
    }

    override suspend fun signInWithOAuth(
        provider: String,
        rememberSession: Boolean,
        scopes: List<String>,
    ): AuthSession {
        val normalizedProvider = provider.trim().lowercase()
        if (!supportsOAuth(normalizedProvider)) {
            throw AuthClientException(
                AuthFailureReason.OAuthUnsupported,
                "OAuth sign-in for provider '$normalizedProvider' is not available on this platform.",
            )
        }
        val authorized = oauthFlow.authorize(normalizedProvider, rememberSession) { redirectUri, challenge ->
            oauthAuthorizeUrl(normalizedProvider, redirectUri, challenge.challenge, scopes)
        }
        return completeOAuthSignIn(authorized.code, authorized.codeVerifier)
    }

    override suspend fun completeOAuthSignIn(code: String, codeVerifier: String): AuthSession {
        val body = buildJsonObject {
            put("auth_code", code)
            put("code_verifier", codeVerifier)
        }.toString()
        val reply = execute {
            httpClient.post("$authUrl/token?grant_type=pkce") {
                header("apikey", settings.anonKey)
                header("Authorization", "Bearer ${settings.anonKey}")
                setBody(TextContent(body, ContentType.Application.Json))
            }
        }
        val user = reply["user"]?.jsonObject
        val email = user?.get("email")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val subject = user?.get("id")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val userId = email ?: subject ?: throw AuthClientException(
            AuthFailureReason.ProviderError,
            "Supabase PKCE token exchange returned neither user email nor user id.",
        )
        return parseSession(fallbackUserId = userId, reply = reply)
    }

    override suspend fun signIn(email: String, password: String): AuthSession {
        val normalizedEmail = email.trim()
        val body = buildJsonObject {
            put("email", normalizedEmail)
            put("password", password)
        }.toString()
        val reply = execute {
            httpClient.post("$authUrl/token?grant_type=password") {
                header("apikey", settings.anonKey)
                header("Authorization", "Bearer ${settings.anonKey}")
                setBody(TextContent(body, ContentType.Application.Json))
            }
        }
        return parseSession(fallbackUserId = normalizedEmail, reply = reply)
    }

    override suspend fun signUp(email: String, password: String): AuthSession {
        val normalizedEmail = email.trim()
        val body = buildJsonObject {
            put("email", normalizedEmail)
            put("password", password)
        }.toString()
        val reply = execute {
            httpClient.post("$authUrl/signup") {
                header("apikey", settings.anonKey)
                header("Authorization", "Bearer ${settings.anonKey}")
                setBody(TextContent(body, ContentType.Application.Json))
            }
        }
        if (reply["access_token"] == null && reply["user"] != null) {
            throw AuthClientException(
                AuthFailureReason.EmailConfirmationRequired,
                "Check your email to confirm your account before signing in.",
                rawCode = "email_not_confirmed",
            )
        }
        return parseSession(fallbackUserId = normalizedEmail, reply = reply)
    }

    override suspend fun refresh(userId: String, refreshToken: String): AuthSession {
        val body = buildJsonObject {
            put("refresh_token", refreshToken)
        }.toString()
        val reply = execute {
            httpClient.post("$authUrl/token?grant_type=refresh_token") {
                header("apikey", settings.anonKey)
                header("Authorization", "Bearer ${settings.anonKey}")
                setBody(TextContent(body, ContentType.Application.Json))
            }
        }
        return parseSession(fallbackUserId = userId.trim(), reply = reply)
    }

    override suspend fun sendPasswordReset(email: String) {
        val body = buildJsonObject {
            put("email", email.trim())
        }.toString()
        val redirectTo = redirectUrlProvider()?.trim()?.takeIf { it.isNotEmpty() }
        execute {
            httpClient.post("$authUrl/recover") {
                if (redirectTo != null) {
                    parameter("redirect_to", redirectTo)
                }
                header("apikey", settings.anonKey)
                header("Authorization", "Bearer ${settings.anonKey}")
                setBody(TextContent(body, ContentType.Application.Json))
            }
        }
    }

    override suspend fun setNewPassword(accessToken: String, password: String) {
        val body = buildJsonObject {
            put("password", password)
        }.toString()
        execute {
            httpClient.put("$authUrl/user") {
                header("apikey", settings.anonKey)
                header("Authorization", "Bearer $accessToken")
                setBody(TextContent(body, ContentType.Application.Json))
            }
        }
    }

    private fun parseSession(fallbackUserId: String, reply: JsonObject): AuthSession {
        val accessToken = reply["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw AuthClientException(
                AuthFailureReason.ProviderError,
                "Supabase response did not include an access_token.",
            )
        val refreshToken = reply["refresh_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val expiresIn = reply["expires_in"]?.jsonPrimitive?.longOrNull ?: DEFAULT_EXPIRES_IN_SECONDS
        val user = reply["user"]?.jsonObject
        val email = user?.get("email")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val subject = user?.get("id")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val resolvedUserId = fallbackUserId.ifBlank { email ?: subject.orEmpty() }
        return AuthSession(
            userId = resolvedUserId,
            accessToken = accessToken,
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
            throw AuthClientException(
                AuthFailureReason.NetworkError,
                "Could not reach the authentication server.",
                cause = transport,
            )
        }
        val text = response.bodyAsText()
        val parsed = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (response.status.value in 200..299) {
            return parsed ?: JsonObject(emptyMap())
        }
        val errorCode = parsed?.get("error_code")?.jsonPrimitive?.contentOrNull
            ?: parsed?.get("code")?.jsonPrimitive?.contentOrNull
            ?: parsed?.get("error")?.jsonPrimitive?.contentOrNull
        val rawMessage = parsed?.get("msg")?.jsonPrimitive?.contentOrNull
            ?: parsed?.get("message")?.jsonPrimitive?.contentOrNull
            ?: parsed?.get("error_description")?.jsonPrimitive?.contentOrNull
            ?: parsed?.get("error")?.jsonPrimitive?.contentOrNull
            ?: ""
        throw classifyFailure(response.status.value, errorCode, rawMessage)
    }

    /** Companion helpers for GoTrue error mapping. */
    public companion object {
        private const val DEFAULT_EXPIRES_IN_SECONDS: Long = 3_600L

        /** Maps a GoTrue HTTP [status], optional [errorCode], and [rawMessage] to a typed [AuthClientException]. */
        public fun classifyFailure(
            status: Int,
            errorCode: String?,
            rawMessage: String,
        ): AuthClientException {
            val code = errorCode?.trim()?.lowercase()
            val reason = when {
                code == "invalid_credentials" ||
                    rawMessage.contains("Invalid login credentials", ignoreCase = true) ->
                    AuthFailureReason.InvalidCredentials

                code == "user_already_exists" ||
                    code == "email_exists" ||
                    rawMessage.contains("already registered", ignoreCase = true) ->
                    AuthFailureReason.AccountAlreadyExists

                code == "weak_password" ||
                    (rawMessage.contains("at least", ignoreCase = true) && rawMessage.contains("character", ignoreCase = true)) ->
                    AuthFailureReason.WeakPassword

                code == "email_address_invalid" ||
                    (code == "validation_failed" && rawMessage.contains("email", ignoreCase = true)) ->
                    AuthFailureReason.InvalidEmail

                code == "user_banned" ->
                    AuthFailureReason.AccountDisabled

                code == "email_not_confirmed" ->
                    AuthFailureReason.EmailConfirmationRequired

                status == 429 ||
                    code == "over_request_rate_limit" ||
                    code == "over_email_send_rate_limit" ||
                    rawMessage.contains("rate limit", ignoreCase = true) ->
                    AuthFailureReason.RateLimited

                code == "refresh_token_not_found" ||
                    code == "refresh_token_already_used" ||
                    code == "otp_expired" ||
                    code == "bad_jwt" ||
                    code == "session_not_found" ||
                    rawMessage.contains("invalid refresh token", ignoreCase = true) ||
                    rawMessage.contains("invalid claim", ignoreCase = true) ||
                    rawMessage.contains("token expired", ignoreCase = true) ->
                    AuthFailureReason.SessionExpired

                else -> AuthFailureReason.ProviderError
            }
            val message = rawMessage.ifBlank { defaultMessageFor(reason) }
            return AuthClientException(reason = reason, message = message, rawCode = errorCode)
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
