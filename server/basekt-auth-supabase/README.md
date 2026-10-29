# basekt-auth-supabase

Supabase Auth access tokens as an identity: a Ktor provider that verifies them against Supabase GoTrue or locally via HMAC-SHA256 JWT secret, and maps user IDs, roles and record-scope attributes to BaseKt callers.

```kotlin
dependencies { implementation("io.github.basekt:basekt-auth-supabase:0.4.0-SNAPSHOT") }
```

Builds on: `basekt-auth`.

## Use

```kotlin
install(Authentication) {
    supabase("supabase", SupabaseSettings.from(environment.config.config("auth.supabase")))
    firebase("firebase", FirebaseSettings.from(environment.config.config("auth.firebase")))   // optional fallback
}
install(BaseKt) {
    identities = AuthIdentities("supabase", "firebase")   // tried in order: caller with either token is identified
}
```

```hocon
auth.supabase {
    url = "https://your-project.supabase.co"
    anonKey = "eyJhbGciOi..."
    jwtSecret = "optional-jwt-secret-for-local-hmac"
    clockSkew = "PT30S"          # optional
    cacheTtl = "PT5M"            # optional, in-memory cache duration for GoTrue validations
    rolesClaim = "roles"         # optional, custom claim
    attributeClaims = ["team"]   # optional, record-scope attributes
}
```

## How it works

- **Token Validation**:
  - If `jwtSecret` is provided, verifies the HMAC-SHA256 signature locally with no network calls.
  - If `jwtSecret` is omitted (only `url` and `anonKey` set), validates against Supabase's `/auth/v1/user` GoTrue endpoint using the JDK's built-in HTTP client and caches verified profiles in memory for `cacheTtl` or until token expiration.
- **Provider Chaining**: Tokens whose issuer does not match Supabase are ignored immediately, allowing subsequent providers in `AuthIdentities` (e.g. Firebase or machine JWTs) to take their turn.
- **Identity Mapping**: The user's UUID (`sub`) becomes the `Identity.id`. Roles and attributes are mapped from custom claims, `app_metadata`, and `user_metadata`.

## Documentation

- [Adapters](https://basekt.github.io/basekt/adapters/)
- [Permissions](https://basekt.github.io/basekt/permissions/)
- [All modules](../../README.md#modules)
