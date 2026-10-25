# Permissions and record scopes

Each resource says who may read and write it, by role, in its `permissions` block:

```kotlin
permissions { read(everyone); write("staff") }
```

A caller's roles come from its `Identity`. With `identities = AuthIdentities("jwt")` in `install(BaseKt)`, they come
from the JWT (see [Adapters](adapters.md#auth)). A caller without credentials gets `401 unauthorized`, one without
the role `403 forbidden`.

## Roles from the server

Some roles don't belong in a token: an operator list kept in configuration, or roles stored as records that change
without a new sign-in. `withServerRoles` adds them to any `IdentityResolver`, after the caller is identified:

```kotlin
install(BaseKt) {
    identities = AuthIdentities("jwt").withServerRoles(
        ServerRoles.from(environment.config.config("basekt.roles")),   // caller ids per role
        ServerRoles { identity, call ->                                 // a lookup, here in a resource
            val runtime = call.application.baseKt
            val memberships = runtime.dataSource.list(
                runtime.schema.resource("memberships")!!,
                Query(Filter.Compare("memberId", Comparison.EQ, Value.Text(identity.id))),
            ).items
            Grants(
                roles = memberships.mapNotNull { (it["role"] as Value.Text?)?.value }.toSet(),
                attributes = mapOf("team" to memberships.mapNotNull { (it["teamId"] as Value.Text?)?.value }.toSet()),
            )
        },
    )
}
```

```hocon
basekt.roles {
    operator = ["uid-1", "uid-2"]
    moderator = ["uid-3"]
}
```

- **Grants only add.** The caller keeps every role and attribute its credentials carry; the server's roles are added
  to them, and an attribute that both name holds the values of both. Sources are asked in order and add up, so none
  can take away what another, or the token, gave. To shut a caller out, a source throws an `ApiException`, such as
  `403 forbidden`, which answers the call.
- **A failed lookup grants nothing.** A source that throws anything else answers the call `503 unavailable`, logged
  with its cause, even when the token's roles alone would allow it: the caller's roles are unknown, not empty.
- **Once per call.** The identity is kept on the call, so resolving it again, from a route or a permission check,
  doesn't ask the sources again. Anonymous calls ask no source.
- **Any provider.** It works on any resolver, `AuthIdentities` with JWT, Firebase or bearer providers among them, and
  keeps the route scope the resolver needs. It is part of `basekt-server`, so it needs no auth module.

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
them; [server roles](#roles-from-the-server) can grant them too. Scopes are schema data like everything else. [Hooks](hooks.md) and repositories are trusted server code, so
scopes don't apply to them.
