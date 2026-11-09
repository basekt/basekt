package io.github.basekt.auth.client

/**
 * Chains a [primary] [AuthClient] with an optional [fallback] for zero-downtime provider migrations (matching a server
 * configured with `AuthIdentities("supabase", "firebase")`).
 *
 * - [signIn] tries [primary] first; if it fails with [AuthFailureReason.InvalidCredentials] and [fallback] is present,
 *   tries [fallback] (re-throwing [primary]'s exception if [fallback] also fails).
 * - [signUp] and [setNewPassword] always target [primary] so new accounts and password resets land on the new provider.
 * - [refresh] tries [primary] first and falls back to [fallback] so saved sessions from before migration keep working.
 * - [sendPasswordReset] sends through [primary] and best-effort through [fallback] so accounts not yet migrated still
 *   receive reset links without disclosing which provider holds the account.
 * - [OAuthCapable] delegates to whichever of [primary] or [fallback] supports the requested OAuth provider.
 */
public class CompositeAuthClient(
    public val primary: AuthClient,
    public val fallback: AuthClient? = null,
    override val id: String = "composite",
) : AuthClient, OAuthCapable {

    init {
        require(id.isNotBlank()) { "CompositeAuthClient.id must not be blank." }
    }

    override val supportedOAuthProviders: Set<String>
        get() = (primary as? OAuthCapable)?.supportedOAuthProviders.orEmpty() +
            (fallback as? OAuthCapable)?.supportedOAuthProviders.orEmpty()

    override fun oauthAuthorizeUrl(
        provider: String,
        redirectUri: String,
        codeChallenge: String,
        scopes: List<String>,
    ): String = oauthDelegateFor(provider).oauthAuthorizeUrl(provider, redirectUri, codeChallenge, scopes)

    override suspend fun signInWithOAuth(
        provider: String,
        rememberSession: Boolean,
        scopes: List<String>,
    ): AuthSession = oauthDelegateFor(provider).signInWithOAuth(provider, rememberSession, scopes)

    override suspend fun completeOAuthSignIn(code: String, codeVerifier: String): AuthSession {
        val delegate = (primary as? OAuthCapable) ?: (fallback as? OAuthCapable)
            ?: throw AuthClientException(AuthFailureReason.OAuthUnsupported, "OAuth sign-in is not configured.")
        return delegate.completeOAuthSignIn(code, codeVerifier)
    }

    override suspend fun signIn(email: String, password: String): AuthSession = try {
        primary.signIn(email, password)
    } catch (refused: AuthClientException) {
        if (fallback != null && refused.reason == AuthFailureReason.InvalidCredentials) {
            try {
                fallback.signIn(email, password)
            } catch (_: AuthClientException) {
                throw refused
            }
        } else {
            throw refused
        }
    }

    override suspend fun signUp(email: String, password: String): AuthSession =
        primary.signUp(email, password)

    override suspend fun refresh(userId: String, refreshToken: String): AuthSession = try {
        primary.refresh(userId, refreshToken)
    } catch (refused: AuthClientException) {
        fallback?.refresh(userId, refreshToken) ?: throw refused
    }

    override suspend fun sendPasswordReset(email: String) {
        primary.sendPasswordReset(email)
        try {
            fallback?.sendPasswordReset(email)
        } catch (_: AuthClientException) {
            // Primary accepted the request; fallback refusals are suppressed so provider membership is never leaked.
        }
    }

    override suspend fun setNewPassword(accessToken: String, password: String) {
        primary.setNewPassword(accessToken, password)
    }

    private fun oauthDelegateFor(provider: String): OAuthCapable {
        val primaryOAuth = primary as? OAuthCapable
        if (primaryOAuth != null && primaryOAuth.supportsOAuth(provider)) return primaryOAuth
        val fallbackOAuth = fallback as? OAuthCapable
        if (fallbackOAuth != null && fallbackOAuth.supportsOAuth(provider)) return fallbackOAuth
        throw AuthClientException(
            AuthFailureReason.OAuthUnsupported,
            "OAuth provider '${provider.trim().lowercase()}' is not supported.",
        )
    }
}
