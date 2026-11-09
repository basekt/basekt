package io.github.basekt.auth.client

/**
 * Standard OAuth 2.0 provider identifiers recognized across [OAuthCapable] adapters.
 */
public object OAuthProviders {
    /** Google OAuth 2.0 (`"google"`). */
    public const val GOOGLE: String = "google"

    /** Apple Sign In (`"apple"`). */
    public const val APPLE: String = "apple"

    /** Discord OAuth 2.0 (`"discord"`). */
    public const val DISCORD: String = "discord"

    /** GitHub OAuth 2.0 (`"github"`). */
    public const val GITHUB: String = "github"
}

/**
 * An authenticated client session holding the bearer [accessToken] verified by a BaseKt server, its expiration
 * timestamp [expiresAtEpochSeconds], and an optional [refreshToken] used to mint subsequent access tokens.
 *
 * Intentionally a regular class rather than a `data class` so bearer and refresh tokens are never printed in logs or
 * exception traces via [toString].
 *
 * @property userId canonical identifier for the signed-in principal (for example an email address or provider subject).
 * @property accessToken short-lived bearer token (JWT / ID token) sent in `Authorization: Bearer <token>`.
 * @property refreshToken long-lived refresh token for minting a new [accessToken], or `null` when non-refreshable.
 * @property expiresAtEpochSeconds epoch second when [accessToken] expires.
 * @property email verified email address reported by the identity provider, when available.
 * @property subject provider-assigned immutable user ID (`sub` / `localId`), when reported in the auth response.
 */
public class AuthSession(
    public val userId: String,
    public val accessToken: String,
    public val refreshToken: String?,
    public val expiresAtEpochSeconds: Long,
    public val email: String? = null,
    public val subject: String? = null,
) {
    init {
        require(userId.isNotBlank()) { "AuthSession.userId must not be blank." }
        require(accessToken.isNotBlank()) { "AuthSession.accessToken must not be blank." }
    }

    override fun toString(): String = "AuthSession(userId=$userId)"
}

/**
 * Typed classification of why a client authentication operation failed, allowing callers and UI layers to map failures
 * to localized messages or fallback logic without matching provider-specific strings.
 */
public enum class AuthFailureReason {
    /** Unknown email or wrong password (unified so sign-in never reveals whether an email is registered). */
    InvalidCredentials,

    /** Sign-up refused because an account already exists for the email address. */
    AccountAlreadyExists,

    /** Sign-up or password update refused because the password failed provider strength rules. */
    WeakPassword,

    /** Email address was missing or syntactically invalid. */
    InvalidEmail,

    /** Password was empty or missing. */
    MissingPassword,

    /** Account has been disabled or banned by the identity provider. */
    AccountDisabled,

    /** Sign-up succeeded, but the provider requires email confirmation before issuing an access token. */
    EmailConfirmationRequired,

    /** Too many authentication or recovery attempts; retry after cooling down. */
    RateLimited,

    /** Refresh token, access token, or password-recovery token is expired or revoked; full sign-in is required. */
    SessionExpired,

    /** User cancelled browser OAuth consent or the local callback listener timed out. */
    OAuthCancelled,

    /** Requested OAuth provider or platform redirect flow is not configured or supported on this host. */
    OAuthUnsupported,

    /** Network failure, DNS/TLS error, or browser fetch failure reaching the identity provider. */
    NetworkError,

    /** Unclassified refusal returned by the identity provider; inspect [AuthClientException.rawCode] or `message`. */
    ProviderError,
}

/**
 * Typed exception thrown by [AuthClient] and [OAuthRedirectFlow] operations when sign-in, sign-up, token refresh,
 * password recovery, or OAuth authorization cannot complete.
 *
 * @property reason structured failure category for programmatic branching and UI message selection.
 * @property rawCode optional provider error code (for example `"INVALID_LOGIN_CREDENTIALS"` or `"otp_expired"`).
 */
public class AuthClientException(
    public val reason: AuthFailureReason,
    message: String,
    public val rawCode: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Provider-neutral client authentication SPI for signing users in, creating accounts, refreshing short-lived bearer
 * tokens, and completing password-reset round trips.
 *
 * Concrete adapters live in their own modules (`basekt-auth-client-supabase`, `basekt-auth-client-firebase`) and issue
 * tokens accepted directly by the matching server adapters (`basekt-auth-supabase`, `basekt-auth-firebase`).
 *
 * Providers that also support browser/deep-link OAuth 2.0 PKCE implement [OAuthCapable].
 */
public interface AuthClient {
    /** Stable identifier for this client adapter (such as `"supabase"` or `"firebase"`). */
    public val id: String

    /** Signs in an existing account with [email] and [password]. Throws [AuthClientException] on failure. */
    public suspend fun signIn(email: String, password: String): AuthSession

    /** Creates a new account with [email] and [password] and signs it in. Throws [AuthClientException] on failure. */
    public suspend fun signUp(email: String, password: String): AuthSession

    /** Mints a fresh [AuthSession] for [userId] using [refreshToken]. Throws [AuthClientException] on failure. */
    public suspend fun refresh(userId: String, refreshToken: String): AuthSession

    /**
     * Requests a password-reset email for [email]. Succeeds identically whether or not [email] has an account so
     * callers never disclose account existence; throws [AuthClientException] only on transport, rate-limit, or
     * configuration errors.
     */
    public suspend fun sendPasswordReset(email: String)

    /** Sets a new [password] using a recovery or active [accessToken]. Throws [AuthClientException] on failure. */
    public suspend fun setNewPassword(accessToken: String, password: String)
}

/**
 * Optional capability implemented by [AuthClient]s that authenticate through OAuth 2.0 Authorization Code with PKCE
 * (`S256`, RFC 7636), such as Sign in with Google, Apple, Discord, or GitHub.
 */
public interface OAuthCapable {
    /** Lowercase OAuth provider names available on the current host (for example `setOf("google")`). */
    public val supportedOAuthProviders: Set<String>

    /** Returns `true` when [provider] (case-insensitive, `"google"` by default) is available on the current host. */
    public fun supportsOAuth(provider: String = OAuthProviders.GOOGLE): Boolean =
        provider.trim().lowercase() in supportedOAuthProviders

    /**
     * Builds the authorization URL for [provider] redirecting back to [redirectUri] with the RFC 7636 `S256`
     * [codeChallenge] and optional OAuth [scopes].
     */
    public fun oauthAuthorizeUrl(
        provider: String,
        redirectUri: String,
        codeChallenge: String,
        scopes: List<String> = emptyList(),
    ): String

    /**
     * Runs the host's [OAuthRedirectFlow] for [provider] and exchanges the returned authorization code and PKCE
     * verifier for an [AuthSession]. Throws [AuthClientException] if unsupported, cancelled, or refused.
     */
    public suspend fun signInWithOAuth(
        provider: String = OAuthProviders.GOOGLE,
        rememberSession: Boolean = false,
        scopes: List<String> = emptyList(),
    ): AuthSession

    /**
     * Exchanges an authorization [code] and PKCE [codeVerifier] captured from a browser or deep-link redirect for an
     * [AuthSession]. Throws [AuthClientException] on failure.
     */
    public suspend fun completeOAuthSignIn(code: String, codeVerifier: String): AuthSession
}
