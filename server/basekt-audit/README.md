# basekt-audit

`install(BaseKtAudit)`: a log of every create, update, delete and action, with who made it, when, and what changed, written in the same transaction as the write.

```kotlin
dependencies { implementation("io.github.basekt:basekt-audit:0.2.0") }
```

Builds on: basekt-server.

## Use

```kotlin
val shop = schema {
    auditLog("audit", readers = setOf("admin"))   // read-only through every API
    // ...
}
install(BaseKt) { /* ... */ }
install(BaseKtAudit) { redact("users.pin"); skip("sessions") }
```

## Good to know

- A write that rolls back leaves no entry. `GET /api/audit?recordId=p1` is one record's history.

## Documentation

- [Audit](https://basekt.github.io/basekt/audit/)
- [All modules](../../README.md#modules)
