# Actions and workflows

## Actions

**Actions** are named operations beyond create, update and delete, such as lending a copy or archiving everything
that's done. The declaration is schema data, with an input checked like a record, who may run it, and whether it runs
on one record or the whole resource. The code is registered at install:

```kotlin
resource("copies") {
    int("available") { required(); min(0) }
    action("lend") {
        input { string("borrower") { required() } }
        allow("librarian")
        confirm("Lend a copy?")
    }
}

install(BaseKt) {
    actions {
        on("copies", "lend") { input ->
            val copy = (record!!["id"] as Value.Text).value
            val anyLeft = Filter.Compare("available", Comparison.GTE, Value.Integer(1))
            if ((data as ConditionalWrites).updateIf(copies, copy, anyLeft, increments = mapOf("available" to Value.Integer(-1)))
                !is ConditionalUpdate.Updated) fail("None left.")
            create(loans, mapOf("copyId" to Value.Text(copy), "borrower" to input["borrower"]))
            null   // answer with the record as it is now
        }
    }
}
```

The action is served at `POST /api/copies/{id}/actions/lend`, or `POST /api/{resource}/actions/{name}` for a
resource action. Everything it writes is one transaction, and `fail` rolls it all back. `create`, `update` and
`delete` in an action go through the same steps as the REST API ([automatic fields](automatic-fields.md), validation,
[hooks](hooks.md), [versions](concurrent-writes.md#versions)), with the action's permission in place of the caller's.
A record action needs the record in the caller's update [scope](permissions.md#record-scopes). The admin API lists
the actions each caller may run, and the panel shows them as buttons, with a form for their input.

## Workflows

**Workflows** make an enum field a state machine, for approvals and anything else that moves through statuses. The
server sets the state: records start in the initial one, clients can't write it, and only named transitions change it:

```kotlin
resource("claims") {
    decimal("amount") { required() }
    string("owner") { createdBy() }
    enum("status", listOf("draft", "pending", "approved", "rejected"))
    workflow("status", initial = "draft") {
        transition("submit", from = "draft", to = "pending") { allow(authenticated) }
        transition("approve", from = "pending", to = "approved") { allow("manager"); input { string("note") } }
        transition("reject", from = "pending", to = "rejected") { allow("manager") }
    }
}

install(BaseKt) {
    actions {   // optional: a transition's guard and side effects, in its transaction
        on("claims", "approve") { if ((record!!["owner"] as Value.Text?)?.value == caller?.id) fail("Not your own claim."); null }
    }
}
```

A transition runs like a record action, at `POST /api/claims/{id}/actions/approve`. It checks the role and the
caller's scope, then the record's state (`409` if the transition can't start from it), then runs the code, if any. The
state then changes through the usual write steps (automatic fields, hooks, versions, [audit](audit.md)), and only if
no other transition moved the record in the meantime. The admin panel shows each record's valid transitions as
buttons.
