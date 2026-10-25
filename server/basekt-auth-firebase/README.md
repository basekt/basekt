# basekt-auth-firebase

Firebase ID tokens as an identity: a Ktor JWT provider that verifies them against Google's rotating public keys, with no Firebase Admin SDK, and maps the uid and custom claims to roles and record-scope attributes.

```kotlin
dependencies { implementation("io.github.basekt:basekt-auth-firebase:0.3.0-SNAPSHOT") }
```

In the latest `main`, published as a snapshot until 0.3.0 is released: see [Getting started](https://basekt.github.io/basekt/getting-started/#snapshots).

Builds on: basekt-auth.

## Use

```kotlin
install(Authentication) {
    firebase("firebase", FirebaseSettings.from(environment.config.config("auth.firebase")))
    baseKtJwt("jwt", JwtSettings.from(environment.config.config("auth.jwt")))   // machine tokens, say
}
install(BaseKt) {
    identities = AuthIdentities("firebase", "jwt")   // tried in order: a caller with either token is identified
}
```

```hocon
auth.firebase {
    projectId = "my-project"
    clockSkew = "PT30S"          # optional
    rolesClaim = "roles"         # optional, a custom claim
    attributeClaims = ["team"]   # optional, record-scope attributes
}
```

## Good to know

- The token's uid is the identity's id. Roles and attributes come from custom claims set with the Admin SDK's `setCustomUserClaims`, as arrays of strings or one string of names.
- Checks: RS256 signature with one of Google's keys, issuer `https://securetoken.google.com/<projectId>`, audience `<projectId>`, `exp`, `iat` and `auth_time`, with the clock skew allowed.
- Keys are fetched on the first Firebase token, not at startup, and cached for the response's `Cache-Control: max-age`. A failed refresh keeps the old keys. Tests pass `FirebaseKeys(fetcher)` with a test key.
- Tokens that aren't this project's Firebase tokens are refused without fetching keys, so the next provider gets its turn.

## Documentation

- [Adapters](https://basekt.github.io/basekt/adapters/#firebase-id-tokens)
- [Permissions](https://basekt.github.io/basekt/permissions/)
- [All modules](../../README.md#modules)
