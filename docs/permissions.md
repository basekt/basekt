# Permissions and record scopes

Each resource says who may read and write it, by role, in its `permissions` block:

```kotlin
permissions { read(everyone); write("staff") }
```

A caller's roles come from its `Identity`. With `identities = AuthIdentities("jwt")` in `install(BaseKt)`, they come
from the JWT (see [Adapters](adapters.md#auth)). A caller without credentials gets `401 unauthorized`, one without
the role `403 forbidden`.

## Record scopes

**Record scopes** narrow permissions down to records. A caller's `Identity` carries attributes besides its roles,
such as the teams or sites they belong to, and a scope says which field of a record must hold one of those values:

```kotlin
resource("appointments") {
    reference("clinicId", "clinics") { required() }
    permissions {
        read("staff"); write("staff")
        scope("clinicId", attribute = "clinic", unless = setOf("director"))   // only your clinics' records
    }
}
resource("entries") {
    string("authorId")
    permissions { read(authenticated); write(authenticated); ownedBy("authorId", on = writes) }  // edit only your own
}
```

Lists, counts and relation routes hold only the records in the caller's scope, and a record outside it is `404`. A
record you may read but not change is `403`, and so is a write that would give a scoped field a value outside the
caller's. A create that leaves the field out gets the caller's value when they have exactly one. With JWTs,
`PrincipalRoles.jwt(attributeClaims = listOf("clinic"))` reads attributes from claims, and `JwtSettings.issue` writes
them. Scopes are schema data like everything else. [Hooks](hooks.md) and repositories are trusted server code, so
scopes don't apply to them.
