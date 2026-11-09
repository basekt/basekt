# basekt-auth-client-supabase

Multiplatform Supabase Auth (GoTrue) client adapter over Ktor `HttpClient`: email/password sign-in and sign-up, refresh tokens, password recovery, and OAuth 2.0 PKCE (`google`, `apple`, `discord`, `github`), issuing access tokens verified directly by `server:basekt-auth-supabase`.

```kotlin
dependencies { implementation("io.github.basekt:basekt-auth-client-supabase:0.17.0") }
```

Builds on: `basekt-auth-client`.

## Use

```kotlin
val auth = SupabaseAuthClient(
    settings = SupabaseClientSettings(
        url = "https://your-project.supabase.co",
        anonKey = "eyJhbGciOi...",
        passwordResetRedirectUrl = "https://app.example.com/reset",
        supportedOAuthProviders = setOf(OAuthProviders.GOOGLE),
    ),
    httpClient = httpClient,
    oauthFlow = LoopbackOAuthFlow(openUrl = desktopBrowser::open),
)

val sessions = SessionManager(auth, store = mySavedSessionStore)
sessions.signInWithOAuth(OAuthProviders.GOOGLE, remember = true)
val bearer = sessions.freshAccessToken()
```

## Documentation

- [Client auth](https://basekt.github.io/basekt/client-auth/)
- [Adapters](https://basekt.github.io/basekt/adapters/)
- [All modules](../../README.md#modules)
