package io.github.basekt.auth.client

import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * Remembered account metadata persisted across runs when a user opts into saving their session. Never stores passwords,
 * and redacts [refreshToken] in [toString].
 *
 * @property userId canonical account identifier.
 * @property refreshToken optional refresh token used to mint a new [AuthSession] without re-entering a password.
 * @property email optional verified email address associated with [userId].
 */
@Serializable
public data class SavedSession(
    public val userId: String,
    public val refreshToken: String? = null,
    public val email: String? = null,
) {
    override fun toString(): String = "SavedSession(userId=$userId)"
}

/**
 * Persistence SPI for remembering a single [SavedSession] in per-user storage (for example OS keychain/DataStore,
 * desktop app-data JSON, or browser `localStorage`).
 */
public interface SavedSessionStore {
    /** Reads the currently persisted [SavedSession], or `null` if none is saved. */
    public fun load(): SavedSession?

    /** Persists [session], or clears the stored session when `null`. */
    public fun save(session: SavedSession?)
}

/**
 * Deterministic in-memory [SavedSessionStore] for tests and ephemeral sessions.
 */
public class InMemorySavedSessionStore(initial: SavedSession? = null) : SavedSessionStore {
    @Volatile
    private var current: SavedSession? = initial

    override fun load(): SavedSession? = current

    override fun save(session: SavedSession?) {
        current = session
    }
}

/**
 * Coroutine-safe coordinator around an [AuthClient] and optional [SavedSessionStore] that holds the active
 * [AuthSession], persists or forgets [SavedSession]s, and refreshes expiring bearer tokens on demand.
 *
 * @property client underlying authentication provider (or [CompositeAuthClient]).
 * @param store optional store for "Remember me" / "Save account" refresh tokens.
 * @property refreshMarginSeconds seconds before [AuthSession.expiresAtEpochSeconds] at which [freshAccessToken]
 *   proactively refreshes the token (`300` seconds / 5 minutes by default).
 * @param nowEpochSeconds clock returning current epoch seconds.
 */
public class SessionManager(
    public val client: AuthClient,
    private val store: SavedSessionStore = InMemorySavedSessionStore(),
    public val refreshMarginSeconds: Long = DEFAULT_REFRESH_MARGIN_SECONDS,
    private val nowEpochSeconds: () -> Long = { Clock.System.now().epochSeconds },
) {
    init {
        require(refreshMarginSeconds >= 0L) { "refreshMarginSeconds ($refreshMarginSeconds) must be non-negative." }
    }

    private val refreshMutex = Mutex()

    /** Saved account loaded at startup and updated whenever [signIn], [signInWithOAuth], or [forgetSavedSession] runs. */
    @Volatile
    public var savedSession: SavedSession? = store.load()
        private set

    /** Active authenticated session in memory, or `null` before signing in or after [signOut]. */
    @Volatile
    public var currentSession: AuthSession? = null
        private set

    /** Whether [ client ] supports OAuth 2.0 PKCE for [provider] (`"google"` by default). */
    public fun supportsOAuth(provider: String = OAuthProviders.GOOGLE): Boolean =
        (client as? OAuthCapable)?.supportsOAuth(provider) == true

    /**
     * Returns `true` when [userId] can sign in without supplying a password — either because [currentSession] already
     * belongs to [userId] in memory or because [savedSession] holds a non-null refresh token for [userId].
     */
    public fun canSignInWithoutPassword(userId: String): Boolean {
        val trimmed = userId.trim()
        if (trimmed.isEmpty()) return false
        return currentSession?.userId == trimmed ||
            savedSession?.takeIf { !it.refreshToken.isNullOrBlank() }?.userId == trimmed
    }

    /**
     * Signs [email] in (or creates it when [create] is `true`), reusing an in-memory or saved refresh token when
     * [password] is empty and [canSignInWithoutPassword] holds, and updates [savedSession] according to [remember].
     */
    public suspend fun signIn(
        email: String,
        password: String,
        create: Boolean = false,
        remember: Boolean = false,
    ): AuthSession {
        val normalized = email.trim()
        val held = currentSession?.takeIf { it.userId == normalized }
        val resolved = when {
            create -> client.signUp(normalized, password)
            password.isEmpty() && held != null -> held
            password.isEmpty() && canSignInWithoutPassword(normalized) -> {
                val refreshToken = savedSession?.refreshToken
                    ?: throw AuthClientException(AuthFailureReason.SessionExpired, "Saved session has no refresh token.")
                client.refresh(normalized, refreshToken)
            }
            else -> client.signIn(normalized, password)
        }
        recordSession(resolved, remember)
        return resolved
    }

    /**
     * Signs in via OAuth 2.0 PKCE for [provider] (`"google"` by default), recording the session and updating
     * [savedSession] when [remember] is `true`.
     */
    public suspend fun signInWithOAuth(
        provider: String = OAuthProviders.GOOGLE,
        remember: Boolean = false,
        scopes: List<String> = emptyList(),
    ): AuthSession {
        val oauth = client as? OAuthCapable
            ?: throw AuthClientException(AuthFailureReason.OAuthUnsupported, "OAuth sign-in is not supported by '${client.id}'.")
        val resolved = oauth.signInWithOAuth(provider = provider, rememberSession = remember, scopes = scopes)
        recordSession(resolved, remember)
        return resolved
    }

    /**
     * Completes a pending OAuth 2.0 PKCE redirect ([code] + [codeVerifier]) and records the resulting [AuthSession].
     */
    public suspend fun completeOAuthSignIn(
        code: String,
        codeVerifier: String,
        remember: Boolean = false,
    ): AuthSession {
        val oauth = client as? OAuthCapable
            ?: throw AuthClientException(AuthFailureReason.OAuthUnsupported, "OAuth sign-in is not supported by '${client.id}'.")
        val resolved = oauth.completeOAuthSignIn(code = code, codeVerifier = codeVerifier)
        recordSession(resolved, remember)
        return resolved
    }

    /**
     * Returns a valid bearer [AuthSession.accessToken] for [currentSession], refreshing it under a single-flight mutex
     * when fewer than [refreshMarginSeconds] remain before [AuthSession.expiresAtEpochSeconds].
     *
     * Throws [IllegalStateException] when no [currentSession] is active, or [AuthClientException] if refreshing fails.
     */
    public suspend fun freshAccessToken(): String = refreshMutex.withLock {
        val session = checkNotNull(currentSession) { "No active AuthSession; sign in first." }
        val refreshToken = session.refreshToken
        if (refreshToken != null && nowEpochSeconds() >= session.expiresAtEpochSeconds - refreshMarginSeconds) {
            val refreshed = client.refresh(session.userId, refreshToken)
            currentSession = refreshed
            if (savedSession?.userId == refreshed.userId) {
                persist(SavedSession(refreshed.userId, refreshed.refreshToken, refreshed.email ?: session.email))
            }
            return@withLock refreshed.accessToken
        }
        session.accessToken
    }

    /** Clears the persisted [savedSession] without ending [currentSession]. */
    public fun forgetSavedSession() {
        persist(null)
    }

    /** Ends the in-memory [currentSession], leaving [savedSession] intact if the user opted to remember their account. */
    public fun signOut() {
        currentSession = null
    }

    private fun recordSession(session: AuthSession, remember: Boolean) {
        currentSession = session
        persist(if (remember) SavedSession(session.userId, session.refreshToken, session.email) else null)
    }

    private fun persist(saved: SavedSession?) {
        store.save(saved)
        savedSession = saved
    }

    /** Defaults for [SessionManager]. */
    public companion object {
        /** Default proactive refresh window (`300` seconds / 5 minutes). */
        public const val DEFAULT_REFRESH_MARGIN_SECONDS: Long = 5 * 60L
    }
}
