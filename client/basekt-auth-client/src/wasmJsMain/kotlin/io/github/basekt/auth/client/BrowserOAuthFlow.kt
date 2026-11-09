@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.basekt.auth.client

/**
 * Browser `window.sessionStorage` implementation of [KeyValueStorage] for `wasmJs` applications, swallowing storage
 * access errors in private/sandboxed browsing contexts.
 */
public object WindowSessionStorage : KeyValueStorage {
    override fun get(key: String): String? = sessionStorageGet(key)

    override fun set(key: String, value: String) {
        sessionStorageSet(key, value)
    }

    override fun remove(key: String) {
        sessionStorageRemove(key)
    }
}

/**
 * Browser `wasmJs` [OAuthRedirectFlow] built on [PageRedirectOAuthCoordinator]: stores the tab's PKCE verifier in
 * [storage] (`window.sessionStorage` by default), navigates the tab to the provider's consent URL, and decodes the
 * callback on page reload via [consumeCurrentWindowCallback] or [consumeCallback].
 *
 * @param redirectUrl returns the page URL the identity provider redirects back to (defaults to the current page's
 *   origin + pathname).
 * @property storageKey `sessionStorage` key for the in-flight PKCE state.
 * @param storage key-value backing store (`WindowSessionStorage` by default).
 * @param navigate browser navigation function (`window.location.assign` by default).
 */
public class BrowserOAuthFlow(
    redirectUrl: () -> String = ::currentPageOriginAndPath,
    public val storageKey: String = PageRedirectOAuthCoordinator.DEFAULT_STORAGE_KEY,
    storage: KeyValueStorage = WindowSessionStorage,
    navigate: (String) -> Unit = ::assignWindowLocation,
) : OAuthRedirectFlow {

    private val coordinator = PageRedirectOAuthCoordinator(
        redirectUrl = redirectUrl,
        storage = storage,
        navigate = navigate,
        storageKey = storageKey,
    )

    override suspend fun authorize(
        provider: String,
        rememberSession: Boolean,
        buildAuthorizeUrl: (redirectUri: String, challenge: PkceChallenge) -> String,
    ): AuthorizedCode = coordinator.authorize(provider, rememberSession, buildAuthorizeUrl)

    /**
     * Checks explicit [search] and [hash] strings for an OAuth callback and clears [storageKey] when matched.
     */
    public fun consumeCallback(search: String, hash: String = ""): PendingOAuthReturn? =
        coordinator.consumeCallback(search, hash)

    /**
     * Reads `window.location.search` and `window.location.hash`, scrubs OAuth callback parameters from the browser
     * address bar via `history.replaceState` when matched, and returns the decoded [PendingOAuthReturn].
     */
    public fun consumeCurrentWindowCallback(): PendingOAuthReturn? {
        val search = currentWindowSearch()
        val hash = currentWindowHash()
        val pending = coordinator.consumeCallback(search, hash) ?: return null
        val cleanedSearch = OAuthCallbackParser.stripOAuthParams(search)
        val cleanedHash = if (OAuthCallbackParser.parseCallback(hash) != null) "" else hash
        replaceWindowHistory("${currentWindowPathname()}$cleanedSearch$cleanedHash")
        return pending
    }
}

private fun sessionStorageGet(key: String): String? =
    js("""{ try { return window.sessionStorage.getItem(key); } catch (_) { return null; } }""")

private fun sessionStorageSet(key: String, value: String): Unit =
    js("""{ try { window.sessionStorage.setItem(key, value); } catch (_) {} }""")

private fun sessionStorageRemove(key: String): Unit =
    js("""{ try { window.sessionStorage.removeItem(key); } catch (_) {} }""")

private fun assignWindowLocation(url: String): Unit =
    js("""{ window.location.assign(url); }""")

private fun currentPageOriginAndPath(): String =
    js("""{ return window.location.origin + window.location.pathname; }""")

private fun currentWindowPathname(): String =
    js("""{ return window.location.pathname || "/"; }""")

private fun currentWindowSearch(): String =
    js("""{ return window.location.search || ""; }""")

private fun currentWindowHash(): String =
    js("""{ return window.location.hash || ""; }""")

private fun replaceWindowHistory(pathAndQuery: String): Unit =
    js("""{ try { window.history.replaceState(null, "", pathAndQuery); } catch (_) {} }""")
