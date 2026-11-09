package io.github.basekt.auth.client.firebase

import kotlinx.serialization.Serializable

/**
 * Configuration for [FirebaseAuthClient]. Redacts [apiKey] in [toString].
 *
 * @property apiKey Firebase project Web API key.
 * @property id adapter identifier (`"firebase"` by default).
 * @property identityBaseUrl Google Identity Toolkit v1 REST base URL.
 * @property secureTokenUrl Google Secure Token v1 refresh endpoint URL.
 */
@Serializable
public data class FirebaseClientSettings(
    public val apiKey: String,
    public val id: String = "firebase",
    public val identityBaseUrl: String = DEFAULT_IDENTITY_BASE_URL,
    public val secureTokenUrl: String = DEFAULT_SECURE_TOKEN_URL,
) {
    /** Normalized Identity Toolkit base URL without trailing slash. */
    public val normalizedIdentityBaseUrl: String = identityBaseUrl.trim().trimEnd('/')

    /** Normalized Secure Token endpoint URL. */
    public val normalizedSecureTokenUrl: String = secureTokenUrl.trim()

    init {
        require(apiKey.isNotBlank()) { "Firebase apiKey must not be blank." }
        require(id.isNotBlank()) { "Firebase client id must not be blank." }
        require(normalizedIdentityBaseUrl.startsWith("http://") || normalizedIdentityBaseUrl.startsWith("https://")) {
            "identityBaseUrl '$identityBaseUrl' must start with http:// or https://."
        }
        require(normalizedSecureTokenUrl.startsWith("http://") || normalizedSecureTokenUrl.startsWith("https://")) {
            "secureTokenUrl '$secureTokenUrl' must start with http:// or https://."
        }
    }

    override fun toString(): String = "FirebaseClientSettings(id=$id)"

    /** Default Google endpoint URLs. */
    public companion object {
        /** Default Identity Toolkit v1 base URL. */
        public const val DEFAULT_IDENTITY_BASE_URL: String = "https://identitytoolkit.googleapis.com/v1"

        /** Default Secure Token v1 endpoint URL. */
        public const val DEFAULT_SECURE_TOKEN_URL: String = "https://securetoken.googleapis.com/v1/token"
    }
}
