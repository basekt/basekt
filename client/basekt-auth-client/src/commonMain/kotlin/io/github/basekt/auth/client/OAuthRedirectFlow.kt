@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.basekt.auth.client

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Platform driver for the browser/redirect leg of OAuth 2.0 PKCE: loopback `127.0.0.1` listener on Desktop JVM,
 * full-page redirect with `sessionStorage` on Web (`wasmJs`), and custom-scheme or Universal/App Link callback via
 * [DeepLinkOAuthFlow] on Android and iOS.
 */
public interface OAuthRedirectFlow {
    /** Whether this host environment can initiate an OAuth redirect and receive its callback. */
    public val isSupported: Boolean
        get() = true

    /**
     * Opens the authorization URL returned by [buildAuthorizeUrl] for this flow's redirect URI and suspends until the
     * authorization code arrives. Cancelling the coroutine aborts any active listener or waiter.
     */
    public suspend fun authorize(
        provider: String,
        rememberSession: Boolean,
        buildAuthorizeUrl: (redirectUri: String, challenge: PkceChallenge) -> String,
    ): AuthorizedCode
}

/**
 * No-op [OAuthRedirectFlow] used when a host has not configured browser/deep-link OAuth redirects.
 */
public object UnsupportedOAuthRedirectFlow : OAuthRedirectFlow {
    override val isSupported: Boolean = false

    override suspend fun authorize(
        provider: String,
        rememberSession: Boolean,
        buildAuthorizeUrl: (redirectUri: String, challenge: PkceChallenge) -> String,
    ): AuthorizedCode = throw AuthClientException(
        AuthFailureReason.OAuthUnsupported,
        "OAuth redirect flow is not configured on this platform.",
    )
}

/**
 * Serializable PKCE state persisted across a full-page web redirect or mobile activity/process recreation. Redacts
 * [verifier] in [toString].
 */
@Serializable
public data class StoredPkceState(
    public val verifier: String,
    public val rememberSession: Boolean = false,
    public val provider: String = OAuthProviders.GOOGLE,
) {
    override fun toString(): String = "StoredPkceState(provider=$provider)"
}

/**
 * Persistence SPI for holding a single in-flight [StoredPkceState] while an external browser handles OAuth consent.
 */
public interface PkceStateStore {
    /** Loads the pending [StoredPkceState], or `null` if none is stored. */
    public fun load(): StoredPkceState?

    /** Stores [state], or clears the pending state when `null`. */
    public fun save(state: StoredPkceState?)
}

/**
 * In-memory [PkceStateStore] suitable for warm-process deep links and unit tests.
 */
public class InMemoryPkceStateStore : PkceStateStore {
    @Volatile
    private var stored: StoredPkceState? = null

    override fun load(): StoredPkceState? = stored

    override fun save(state: StoredPkceState?) {
        stored = state
    }
}

/**
 * Deep-link [OAuthRedirectFlow] for Android (`intent-filter` custom scheme / App Links), iOS (custom URL scheme /
 * Universal Links), or desktop protocol handlers.
 *
 * Instance-scoped with zero global state:
 * - Warm return (app stays alive while the system browser is open): [onCallbackUri] completes the suspended [authorize]
 *   call directly.
 * - Cold return (OS reclaimed the backgrounded activity/process while in the browser): [onCallbackUri] pairs the
 *   callback URL with the [StoredPkceState] from [stateStore] and records a one-shot [PendingOAuthReturn] retrieved via
 *   [takePendingReturn].
 *
 * @property callbackUri registered redirect URI (for example `"com.example.app://auth/callback"`).
 * @param openUrl host callback that launches the system browser / Custom Tab / `SFSafariViewController` for a URL.
 * @param stateStore persistence for the PKCE verifier across OS process recreation.
 * @param randomBytes entropy source for [PkceChallenge.generate] (injectable for deterministic tests).
 */
public class DeepLinkOAuthFlow(
    public val callbackUri: String,
    private val openUrl: (String) -> Unit,
    private val stateStore: PkceStateStore = InMemoryPkceStateStore(),
    private val randomBytes: (() -> ByteArray)? = null,
) : OAuthRedirectFlow {

    init {
        require(callbackUri.isNotBlank() && "://" in callbackUri) {
            "callbackUri '$callbackUri' must be a valid URI with a scheme (such as 'com.example.app://auth/callback')."
        }
    }

    private val activeWaiter = AtomicReference<CompletableDeferred<OAuthCallbackResult>?>(null)
    private val pendingReturn = AtomicReference<PendingOAuthReturn?>(null)

    override suspend fun authorize(
        provider: String,
        rememberSession: Boolean,
        buildAuthorizeUrl: (redirectUri: String, challenge: PkceChallenge) -> String,
    ): AuthorizedCode {
        val normalizedProvider = provider.trim().lowercase()
        val challenge = if (randomBytes != null) PkceChallenge.generate(randomBytes) else PkceChallenge.generate()
        val waiter = CompletableDeferred<OAuthCallbackResult>()
        activeWaiter.exchange(waiter)?.cancel()
        stateStore.save(StoredPkceState(challenge.verifier, rememberSession, normalizedProvider))
        try {
            openUrl(buildAuthorizeUrl(callbackUri, challenge))
            return when (val callback = waiter.await()) {
                is OAuthCallbackResult.Code -> AuthorizedCode(callback.code, challenge.verifier)
                OAuthCallbackResult.Cancelled -> throw AuthClientException(
                    AuthFailureReason.OAuthCancelled,
                    "Sign-in was cancelled.",
                )
                is OAuthCallbackResult.Failed -> throw AuthClientException(
                    AuthFailureReason.ProviderError,
                    callback.message,
                    rawCode = callback.errorCode,
                )
            }
        } finally {
            if (activeWaiter.compareAndSet(waiter, null)) {
                stateStore.save(null)
            }
        }
    }

    /**
     * Delivers an incoming deep-link [uri] from the host platform (for example Android `Activity.onCreate` /
     * `onNewIntent` or iOS `onOpenURL`). Returns `true` when [uri] matched [callbackUri] and carried an OAuth callback.
     */
    public fun onCallbackUri(uri: String?): Boolean {
        if (uri == null || !uri.startsWith(callbackUri)) return false
        val parsed = OAuthCallbackParser.parseCallback(uri) ?: return false
        val waiter = activeWaiter.exchange(null)
        val stored = stateStore.load()
        stateStore.save(null)
        if (waiter != null && waiter.complete(parsed)) return true
        pendingReturn.store(toPendingReturn(parsed, stored))
        return true
    }

    /** Consumes and clears any cold-start [PendingOAuthReturn] captured by [onCallbackUri] before a UI opened. */
    public fun takePendingReturn(): PendingOAuthReturn? = pendingReturn.exchange(null)
}

/**
 * Simple string key-value storage abstraction backing [PageRedirectOAuthCoordinator] (implemented by browser
 * `window.sessionStorage` on `wasmJs`).
 */
public interface KeyValueStorage {
    /** Reads the value stored at [key], or `null` when absent or inaccessible. */
    public fun get(key: String): String?

    /** Writes [value] at [key]. */
    public fun set(key: String, value: String)

    /** Deletes [key]. */
    public fun remove(key: String)
}

/**
 * Full-page redirect OAuth 2.0 PKCE coordinator for browser applications: writes [StoredPkceState] to [storage] before
 * navigating away via [navigate], then pairs the returning URL's `?search` or `#hash` with the stored verifier via
 * [consumeCallback].
 *
 * Kept in `commonMain` so redirect/storage edge cases run in fast multiplatform unit tests alongside `BrowserOAuthFlow`
 * on `wasmJs`.
 */
public class PageRedirectOAuthCoordinator(
    private val redirectUrl: () -> String,
    private val storage: KeyValueStorage,
    private val navigate: (String) -> Unit,
    public val storageKey: String = DEFAULT_STORAGE_KEY,
    private val randomBytes: (() -> ByteArray)? = null,
) : OAuthRedirectFlow {

    init {
        require(storageKey.isNotBlank()) { "storageKey must not be blank." }
    }

    override suspend fun authorize(
        provider: String,
        rememberSession: Boolean,
        buildAuthorizeUrl: (redirectUri: String, challenge: PkceChallenge) -> String,
    ): AuthorizedCode {
        val targetRedirect = redirectUrl().trim()
        require(targetRedirect.startsWith("http://") || targetRedirect.startsWith("https://")) {
            "Browser OAuth redirectUrl '$targetRedirect' must start with http:// or https://."
        }
        val challenge = if (randomBytes != null) PkceChallenge.generate(randomBytes) else PkceChallenge.generate()
        val encoded = Json.encodeToString(
            StoredPkceState(
                verifier = challenge.verifier,
                rememberSession = rememberSession,
                provider = provider.trim().lowercase(),
            ),
        )
        storage.set(storageKey, encoded)
        navigate(buildAuthorizeUrl(targetRedirect, challenge))
        awaitCancellation()
    }

    /**
     * Checks the current page's [search] (`window.location.search`) and [hash] (`window.location.hash`) on startup.
     * When an OAuth callback is present, removes [storageKey] from [storage] and returns the decoded
     * [PendingOAuthReturn] so the caller can scrub OAuth parameters from the address bar and finish sign-in.
     */
    public fun consumeCallback(search: String, hash: String = ""): PendingOAuthReturn? {
        val stored = storage.get(storageKey)?.let { raw ->
            runCatching { Json.decodeFromString<StoredPkceState>(raw) }.getOrNull()
        }
        val fromSearch = OAuthCallbackParser.parseCallback(search)
        val fromHash = if (stored != null) OAuthCallbackParser.parseCallback(hash) else null
        val callback = fromSearch ?: fromHash ?: return null
        storage.remove(storageKey)
        return toPendingReturn(callback, stored)
    }

    /** Defaults for [PageRedirectOAuthCoordinator]. */
    public companion object {
        /** Default `sessionStorage` key for storing the in-flight PKCE verifier. */
        public const val DEFAULT_STORAGE_KEY: String = "basekt.auth.oauthPkce"
    }
}

internal fun toPendingReturn(callback: OAuthCallbackResult, stored: StoredPkceState?): PendingOAuthReturn =
    when (callback) {
        is OAuthCallbackResult.Code -> {
            if (stored != null && stored.verifier.isNotBlank()) {
                PendingOAuthReturn.Code(
                    code = callback.code,
                    codeVerifier = stored.verifier,
                    rememberSession = stored.rememberSession,
                    provider = stored.provider,
                )
            } else {
                PendingOAuthReturn.Failed(
                    reason = AuthFailureReason.SessionExpired,
                    message = "OAuth session expired before returning; please sign in again.",
                )
            }
        }
        OAuthCallbackResult.Cancelled -> PendingOAuthReturn.Cancelled
        is OAuthCallbackResult.Failed -> PendingOAuthReturn.Failed(
            reason = AuthFailureReason.ProviderError,
            message = callback.message,
            rawCode = callback.errorCode,
        )
    }
