@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.github.basekt.auth.client

import io.ktor.http.decodeURLQueryComponent
import kotlin.io.encoding.Base64
import kotlin.io.encoding.Base64.PaddingOption
import kotlin.uuid.Uuid

/**
 * OAuth 2.0 PKCE pair (RFC 7636) for a single sign-in attempt: [verifier] stays on the client until the redirect
 * returns, while [challenge] (`S256`) is sent on the authorization URL. Never a `data class` so [verifier] never prints.
 *
 * @property verifier high-entropy secret string (`43..128` URL-safe characters).
 * @property challenge `BASE64URL-ENCODE(SHA256(ASCII(verifier)))` without `=` padding.
 */
public class PkceChallenge(
    public val verifier: String,
    public val challenge: String,
) {
    init {
        require(verifier.length in MIN_VERIFIER_LENGTH..MAX_VERIFIER_LENGTH) {
            "PKCE verifier length (${verifier.length}) must be between $MIN_VERIFIER_LENGTH and $MAX_VERIFIER_LENGTH characters."
        }
        require(challenge.isNotBlank()) { "PKCE challenge must not be blank." }
    }

    override fun toString(): String = "PkceChallenge(***)"

    /** Factories for RFC 7636 `S256` PKCE pairs. */
    public companion object {
        private const val MIN_VERIFIER_LENGTH: Int = 43
        private const val MAX_VERIFIER_LENGTH: Int = 128
        private val urlSafeNoPad = Base64.UrlSafe.withPadding(PaddingOption.ABSENT)

        /**
         * Generates a fresh PKCE [verifier] (`48` base64url characters from 36 cryptographically random bytes by
         * default) paired with its `S256` [challenge].
         */
        public fun generate(randomBytes: () -> ByteArray = ::defaultRandomBytes): PkceChallenge {
            val bytes = randomBytes()
            require(bytes.size >= 32) { "PKCE random entropy (${bytes.size} bytes) must be at least 32 bytes." }
            val verifier = urlSafeNoPad.encode(bytes)
            return PkceChallenge(verifier = verifier, challenge = challengeFor(verifier))
        }

        /** Computes the RFC 7636 `S256` code challenge (`BASE64URL-ENCODE(SHA256(ASCII(verifier)))`). */
        public fun challengeFor(verifier: String): String =
            urlSafeNoPad.encode(Sha256.digest(verifier.encodeToByteArray()))

        private fun defaultRandomBytes(): ByteArray {
            val out = ByteArray(36)
            Uuid.random().toByteArray().copyInto(out, destinationOffset = 0)
            Uuid.random().toByteArray().copyInto(out, destinationOffset = 16)
            Uuid.random().toByteArray().copyInto(out, destinationOffset = 32, startIndex = 0, endIndex = 4)
            return out
        }
    }
}

/**
 * Authorization [code] returned by an OAuth 2.0 callback paired with the PKCE [codeVerifier] that initiated it. Never a
 * `data class` so credentials are redacted in [toString].
 */
public class AuthorizedCode(
    public val code: String,
    public val codeVerifier: String,
) {
    init {
        require(code.isNotBlank()) { "AuthorizedCode.code must not be blank." }
        require(codeVerifier.isNotBlank()) { "AuthorizedCode.codeVerifier must not be blank." }
    }

    override fun toString(): String = "AuthorizedCode(***)"
}

/**
 * Decoded result of an OAuth 2.0 callback URL's query or fragment ([OAuthCallbackParser.parseCallback]).
 */
public sealed interface OAuthCallbackResult {
    /** Successful callback carrying an authorization [code]. */
    public class Code(public val code: String) : OAuthCallbackResult {
        override fun toString(): String = "OAuthCallbackResult.Code(***)"
    }

    /** User declined or dismissed the consent screen (`error=access_denied` without `error_code=otp_expired`). */
    public data object Cancelled : OAuthCallbackResult

    /** Provider returned an OAuth error with a human-readable [message] and optional [errorCode]. */
    public data class Failed(
        public val message: String,
        public val errorCode: String? = null,
    ) : OAuthCallbackResult
}

/**
 * OAuth 2.0 PKCE return captured when a browser full-page redirect or mobile deep link reopens the application, ready
 * to be exchanged once via [OAuthCapable.completeOAuthSignIn].
 */
public sealed interface PendingOAuthReturn {
    /** Authorization [code] paired with the stored PKCE [codeVerifier], [rememberSession] flag, and [provider] id. */
    public class Code(
        public val code: String,
        public val codeVerifier: String,
        public val rememberSession: Boolean = false,
        public val provider: String = OAuthProviders.GOOGLE,
    ) : PendingOAuthReturn {
        override fun toString(): String = "PendingOAuthReturn.Code(provider=$provider)"
    }

    /** User cancelled the consent flow. */
    public data object Cancelled : PendingOAuthReturn

    /** Redirect carried an OAuth error or lacked a matching PKCE verifier. */
    public data class Failed(
        public val reason: AuthFailureReason,
        public val message: String,
        public val rawCode: String? = null,
    ) : PendingOAuthReturn
}

/**
 * Stateless URL parser and query sanitizer for OAuth 2.0 authorization-code callbacks.
 */
public object OAuthCallbackParser {
    private val oauthParams = setOf(
        "code",
        "error",
        "error_code",
        "error_description",
        "state",
        "provider_token",
        "provider_refresh_token",
    )

    /**
     * Parses an [OAuthCallbackResult] from a full callback URL, `?query`, or `#fragment`. Returns `null` when neither
     * `code` nor `error` is present, or when the fragment is a password-recovery redirect (`type=recovery`).
     */
    public fun parseCallback(urlOrParams: String?): OAuthCallbackResult? {
        if (urlOrParams.isNullOrBlank()) return null
        val raw = when {
            '?' in urlOrParams && !urlOrParams.startsWith("?") && !urlOrParams.startsWith("#") ->
                urlOrParams.substringAfter('?').substringBefore('#')
            urlOrParams.startsWith("?") -> urlOrParams.removePrefix("?").substringBefore('#')
            urlOrParams.startsWith("#") -> urlOrParams.removePrefix("#")
            else -> urlOrParams
        }
        val fields = parseUrlEncodedFields(raw)
        if (fields["type"] == "recovery") return null
        val code = fields["code"]?.takeIf { it.isNotBlank() }
        if (code != null) return OAuthCallbackResult.Code(code)
        val error = fields["error"]?.takeIf { it.isNotBlank() } ?: return null
        val errorCode = fields["error_code"]?.takeIf { it.isNotBlank() }
        val description = fields["error_description"]?.takeIf { it.isNotBlank() }
        return if (error.equals("access_denied", ignoreCase = true) && errorCode != "otp_expired") {
            OAuthCallbackResult.Cancelled
        } else {
            OAuthCallbackResult.Failed(message = description ?: error, errorCode = errorCode ?: error)
        }
    }

    /**
     * Removes OAuth callback parameters (`code`, `error`, `error_description`, `state`, ...) from a URL `?search`
     * string while preserving unrelated application query parameters.
     */
    public fun stripOAuthParams(search: String): String {
        val trimmed = search.removePrefix("?")
        if (trimmed.isBlank()) return ""
        val kept = trimmed.split('&').filter { pair ->
            val name = pair.substringBefore('=')
            name.isNotEmpty() && name !in oauthParams
        }
        return if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
    }
}

/**
 * Decoded result of a password-recovery URL fragment ([RecoveryLinkParser.parse]). Never a `data class` so recovery
 * tokens never print.
 */
public sealed interface RecoveryLink {
    /** Active recovery link whose [accessToken] authorizes `setNewPassword`. */
    public class Token(public val accessToken: String) : RecoveryLink {
        init {
            require(accessToken.isNotBlank()) { "RecoveryLink.Token.accessToken must not be blank." }
        }

        override fun toString(): String = "RecoveryLink.Token(***)"
    }

    /** Expired or already-used recovery link (`error_code=otp_expired` or `error=access_denied`). */
    public data object Expired : RecoveryLink
}

/**
 * Stateless parser for password-reset callback URL fragments (such as Supabase GoTrue's `#access_token=...&type=recovery`).
 */
public object RecoveryLinkParser {
    /**
     * Reads a [RecoveryLink] from a URL [fragment] (with or without `#`), returning `null` for unrelated fragments.
     */
    public fun parse(fragment: String?): RecoveryLink? {
        if (fragment.isNullOrBlank()) return null
        val fields = parseUrlEncodedFields(fragment.removePrefix("#"))
        val token = fields["access_token"]?.takeIf { it.isNotBlank() }
        return when {
            fields["type"] == "recovery" && token != null -> RecoveryLink.Token(token)
            fields["error_code"] == "otp_expired" || fields["error"] == "access_denied" -> RecoveryLink.Expired
            else -> null
        }
    }
}

internal fun parseUrlEncodedFields(raw: String): Map<String, String> =
    raw.split('&').mapNotNull { pair ->
        val name = pair.substringBefore('=', missingDelimiterValue = "")
        if (name.isEmpty()) {
            null
        } else {
            runCatching {
                name to pair.substringAfter('=', "").decodeURLQueryComponent(plusIsSpace = true)
            }.getOrNull()
        }
    }.toMap()
