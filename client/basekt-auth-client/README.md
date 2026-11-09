# basekt-auth-client

Multiplatform client authentication SPI (`jvm`/Android, `wasmJs`, `iosArm64`, `iosSimulatorArm64`): `AuthClient`, `OAuthCapable`, `CompositeAuthClient`, `SessionManager`, RFC 7636 OAuth 2.0 PKCE (`S256`), and platform redirect flows (`LoopbackOAuthFlow`, `BrowserOAuthFlow`, `DeepLinkOAuthFlow`).

```kotlin
dependencies { implementation("io.github.basekt:basekt-auth-client:0.17.0-SNAPSHOT") }
```

## What it provides

- **`AuthClient` & `OAuthCapable`**: Provider-neutral contracts for email/password sign-in, sign-up, token refresh, password recovery, and OAuth 2.0 PKCE (`google`, `apple`, `discord`, `github`), throwing typed `AuthClientException` (`AuthFailureReason`).
- **`SessionManager`**: Single-flight (`Mutex`) bearer token refresher and optional `SavedSessionStore` coordinator for "Remember me" sessions without ever storing passwords.
- **`CompositeAuthClient`**: Chains a primary and fallback `AuthClient` (matching a server's `AuthIdentities("supabase", "firebase")`) for zero-downtime identity migrations.
- **Platform OAuth 2.0 PKCE flows**:
  - `LoopbackOAuthFlow` (`jvmMain`): RFC 8252 §7.3 ephemeral `127.0.0.1` listener built on `java.base` sockets (`jlink`-ready).
  - `BrowserOAuthFlow` (`wasmJsMain`): `window.sessionStorage` PKCE persistence + `history.replaceState` URL scrubbing.
  - `DeepLinkOAuthFlow` (`commonMain`): Warm and cold-restart deep-link callback handler for Android (`intent-filter` / App Links) and iOS (Universal Links / custom URL schemes).

## Documentation

- [Client auth](https://basekt.github.io/basekt/client-auth/)
- [All modules](../../README.md#modules)
