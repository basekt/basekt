# basekt-auth

Ktor Authentication adapters (JWT and bearer tokens) that map roles and scope attributes from the caller to schema permissions, with typed 401 and 403 answers.

```kotlin
dependencies { implementation("io.github.basekt:basekt-auth:0.2.0") }
```

Builds on: basekt-server.

## Use

```kotlin
install(Authentication) {
    baseKtJwt("jwt", JwtSettings.from(environment.config.config("auth.jwt")))
}
install(BaseKt) {
    identities = AuthIdentities("jwt")   // roles come from the token, and the permissions check them
}
```

## Good to know

- `PrincipalRoles.jwt(attributeClaims = listOf("clinic"))` reads [record scope](https://basekt.github.io/basekt/permissions/) attributes from claims.
- `bearerIdentities("keys") { token -> ... }` takes fixed API keys.
- Roles kept on the server, such as an operator list in configuration, go on with `AuthIdentities("jwt").withServerRoles(...)` from basekt-server; see [Roles from the server](https://basekt.github.io/basekt/permissions/#roles-from-the-server).

## Documentation

- [Adapters](https://basekt.github.io/basekt/adapters/)
- [Permissions](https://basekt.github.io/basekt/permissions/)
- [All modules](../../README.md#modules)
