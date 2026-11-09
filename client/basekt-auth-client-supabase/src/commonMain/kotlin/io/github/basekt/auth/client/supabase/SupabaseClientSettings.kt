package io.github.basekt.auth.client.supabase

import io.github.basekt.auth.client.OAuthProviders
import kotlinx.serialization.Serializable

/**
 * Configuration for [SupabaseAuthClient]. Redacts [anonKey] in [toString].
 *
 * @property url Supabase project URL (such as `"https://xyzcompany.supabase.co"`).
 * @property anonKey Supabase public `anon` / publishable API key sent in `apikey` and `Authorization` headers.
 * @property id adapter identifier (`"supabase"` by default).
 * @property passwordResetRedirectUrl optional URL GoTrue emails link back to with `#access_token=...&type=recovery`.
 * @property supportedOAuthProviders OAuth provider names enabled on the Supabase project (`setOf("google")` by default).
 */
@Serializable
public data class SupabaseClientSettings(
    public val url: String,
    public val anonKey: String,
    public val id: String = "supabase",
    public val passwordResetRedirectUrl: String? = null,
    public val supportedOAuthProviders: Set<String> = setOf(OAuthProviders.GOOGLE),
) {
    /** Normalized GoTrue `/auth/v1` base endpoint URL. */
    public val authBaseUrl: String = "${url.trim().trimEnd('/')}/auth/v1"

    /** Normalized lowercase OAuth provider names. */
    public val normalizedOAuthProviders: Set<String> = supportedOAuthProviders
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    init {
        val trimmedUrl = url.trim()
        require(trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://")) {
            "Supabase url '$url' must start with http:// or https://."
        }
        require(anonKey.isNotBlank()) { "Supabase anonKey must not be blank." }
        require(id.isNotBlank()) { "Supabase client id must not be blank." }
        if (passwordResetRedirectUrl != null) {
            require(passwordResetRedirectUrl.isNotBlank() && "://" in passwordResetRedirectUrl) {
                "passwordResetRedirectUrl '$passwordResetRedirectUrl' must be a valid URI with a scheme."
            }
        }
    }

    override fun toString(): String = "SupabaseClientSettings(id=$id, url=${url.trim()})"
}
