# basekt-auth-client-firebase

Multiplatform Firebase Auth client adapter over Ktor `HttpClient`: Identity Toolkit email/password sign-in, sign-up, password reset, and CORS-safe Secure Token refresh with zero Firebase platform SDK dependencies, issuing ID tokens verified directly by `server:basekt-auth-firebase`.

```kotlin
dependencies { implementation("io.github.basekt:basekt-auth-client-firebase:0.17.0") }
```

Builds on: `basekt-auth-client`.

## Use

```kotlin
val auth = FirebaseAuthClient(
    settings = FirebaseClientSettings(apiKey = "AIzaSy..."),
    httpClient = httpClient,
)

val sessions = SessionManager(auth, store = mySavedSessionStore)
sessions.signIn("user@example.com", "correct-horse-battery", remember = true)
val bearer = sessions.freshAccessToken()
```

## Documentation

- [Client auth](https://basekt.github.io/basekt/client-auth/)
- [Adapters](https://basekt.github.io/basekt/adapters/#firebase-id-tokens)
- [All modules](../../README.md#modules)
