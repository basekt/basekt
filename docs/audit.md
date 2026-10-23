# Audit

**Audit** (`basekt-audit`) logs every create, update, delete and action, from every transport, as entries of a
resource in your own schema:

```kotlin
resources {
    auditLog("audit", readers = setOf("admin"))   // read-only through every API
    // ...
}
install(BaseKt) { /* ... */ }
install(BaseKtAudit) { redact("users.pin"); skip("sessions") }
```

Each entry has when it happened, the actor, the operation, the resource and record, and what changed: a create's
fields, an update's `from` and `to` for each changed field, or what a delete removed. An action's entry holds its
input, and shares an `operationId` with the writes it made.

Entries are written in the same transaction as the write, so a write that rolls back leaves no entry. Because the log
is an ordinary resource, `GET /api/audit?recordId=p1` gives one record's history, and the admin panel lists it.
