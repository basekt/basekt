# Client authentication

`client:basekt-auth-client` and its provider adapters (`client:basekt-auth-client-supabase`, `client:basekt-auth-client-firebase`) are Kotlin Multiplatform client libraries (`jvm`/Android, `wasmJs`, `iosArm64`, `iosSimulatorArm64`) that mint and refresh the exact bearer tokens verified on the backend by `server:basekt-auth-supabase` and `server:basekt-auth-firebase`.

They depend only on `ktor-client-core`, `kotlinx.coroutines`, and `kotlinx.serialization` — no Android/iOS/JS vendor SDKs, no global state, and no opinion on your UI or HTTP engine.

## Installing

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.basekt:basekt-auth-client:0.17.0")
            implementation("io.github.basekt:basekt-auth-client-supabase:0.17.0")
            // Optional, standalone or alongside Supabase for provider migration:
            implementation("io.github.basekt:basekt-auth-client-firebase:0.17.0")
        }
    }
}
```

Pass the platform's Ktor `HttpClient` (`CIO` or `OkHttp` on JVM/Android, `Darwin` on iOS, `Js` on `wasmJs`).

## Signing in and refreshing bearer tokens

`SessionManager` holds the active `AuthSession`, optionally persists a refresh token in a `SavedSessionStore` when the user ticks "Remember me" (never storing passwords), and refreshes expiring tokens under a single-flight `Mutex`:

```kotlin
val supabase = SupabaseAuthClient(
    settings = SupabaseClientSettings(
        url = "https://your-project.supabase.co",
        anonKey = "eyJhbGciOi...",
        passwordResetRedirectUrl = "https://app.example.com/",
    ),
    httpClient = httpClient,
    oauthFlow = oauthFlow,
)

val sessions = SessionManager(
    client = supabase,
    store = savedSessionStore,
)

// Password sign-in (or passwordless resume when sessions.canSignInWithoutPassword(email) is true):
sessions.signIn(email = "ana@example.com", password = "secret", remember = true)

// Always returns a fresh bearer token, refreshing automatically within 5 minutes of expiry:
val token = sessions.freshAccessToken()
```

Every `AuthSession`, `PkceChallenge`, `AuthorizedCode`, `RecoveryLink.Token`, and `SavedSession` redacts secret tokens and verifiers in `toString()`.

## OAuth 2.0 PKCE across Desktop, Web, Android, and iOS

`OAuthCapable` (`SupabaseAuthClient` and `CompositeAuthClient`) runs RFC 7636 `S256` Authorization Code with PKCE through an injected `OAuthRedirectFlow`:

| Platform | Flow | Redirect URI & Mechanism |
|---|---|---|
| Desktop JVM / CLI | `LoopbackOAuthFlow` | Binds `http://127.0.0.1:<ephemeral-port>/callback` on `java.base` sockets (`jlink`-compatible) and closes the port immediately on callback, timeout, or coroutine cancellation. |
| Web (`wasmJs`) | `BrowserOAuthFlow` | Saves the tab's PKCE verifier in `window.sessionStorage`, redirects to the provider, and scrubs `?code=...` via `history.replaceState` in `consumeCurrentWindowCallback()`. |
| Android & iOS | `DeepLinkOAuthFlow` | Opens `com.example.app://auth/callback` (or an App/Universal Link) and completes warm returns in-memory or cold process-recreation returns via `PkceStateStore` + `takePendingReturn()`. |

### Web startup callback (`wasmJs`)

```kotlin
val browserOAuth = BrowserOAuthFlow()

// On startup, before mounting the login screen:
when (val pending = browserOAuth.consumeCurrentWindowCallback()) {
    is PendingOAuthReturn.Code ->
        sessions.completeOAuthSignIn(pending.code, pending.codeVerifier, remember = pending.rememberSession)
    PendingOAuthReturn.Cancelled -> showNotice("Sign-in was cancelled.")
    is PendingOAuthReturn.Failed -> showError(pending.message)
    null -> Unit
}
```

### Android / iOS deep-link callback

```kotlin
val deepLinkOAuth = DeepLinkOAuthFlow(
    callbackUri = "com.example.app://auth/callback",
    openUrl = ::openSystemBrowser,
    stateStore = fileBackedPkceStateStore,
)

// Forward incoming deep links from Activity.onCreate / Activity.onNewIntent or iOS onOpenURL:
deepLinkOAuth.onCallbackUri(incomingUriString)
```

## Migrating providers with `CompositeAuthClient`

When migrating existing users from Firebase Auth to Supabase Auth while the backend accepts `AuthIdentities("supabase", "firebase")`, wrap both clients in `CompositeAuthClient`:

```kotlin
val auth = CompositeAuthClient(primary = supabase, fallback = firebase)
```

- `signIn` tries `primary` first and falls back to `fallback` on `AuthFailureReason.InvalidCredentials`.
- `refresh` tries `primary` first and falls back to `fallback` so pre-migration refresh tokens stay valid.
- `sendPasswordReset` dispatches to both providers while suppressing fallback errors so neither account membership nor provider placement is leaked.
- `signUp`, `setNewPassword`, and `OAuthCapable` route to `primary`.

## Typed error handling

All failures throw `AuthClientException` with a structured `AuthFailureReason` (`InvalidCredentials`, `AccountAlreadyExists`, `WeakPassword`, `InvalidEmail`, `MissingPassword`, `AccountDisabled`, `EmailConfirmationRequired`, `RateLimited`, `SessionExpired`, `OAuthCancelled`, `OAuthUnsupported`, `NetworkError`, `ProviderError`) and optional `rawCode` so UI code can localize messages without matching English strings.
