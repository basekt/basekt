# Hooks

**Hooks** run in the service layer, so the REST API, the admin API and any later transport run the same ones. A
before-hook on create, update or delete can modify the input or veto the write with a reason. An after-hook runs in
the same transaction.

```kotlin
install(BaseKt) {
    // ...
    hooks {
        beforeUpdate("products") { update ->
            if (update.changes["price"] == null) proceed(update.changes) else veto("price", "Prices are frozen.")
        }
    }
}
```

On a [typed model](schema.md#typed-models), a hook gets the old and new records as instances of the class:

```kotlin
hooks { beforeUpdate { old: Product, new: Product -> if (new.price < old.price) veto("price", "No discounts.") else proceed(new) } }
```

A veto reaches the client as a typed error on its field, and the [admin panel](admin-panel.md) shows it on that field.
[Automatic fields](automatic-fields.md) are filled before the before-hooks, so hooks see them. A delete's
[cascades](schema.md#deleting-records) run each removed record's delete hooks, and each cleared reference's update
hooks, in the same transaction, so a veto from any of them refuses the whole delete.

## After commit

Before- and after-hooks run inside the write's transaction, so a hook that tells another system of a change (a message
queue, a cache, a running process) could do it for a write that then rolls back, or more than once when the data
source runs the transaction again after a conflict. An **after-commit hook** runs once the transaction has committed:

```kotlin
hooks {
    afterCommit("plots") { change ->
        // change.operation is CREATE, UPDATE or DELETE; change.record is the record now (null after a delete),
        // change.before the record before (null after a create).
        notifier.send(resource.name, change.id, change.operation)
    }
}
```

- It runs **once for each record written**, whichever transport wrote it: REST, the admin API, an
  [action](actions-workflows.md)'s writes, and each record a delete [cascades](schema.md#deleting-records) to or clears.
- It **never runs for a write that rolled back**: a veto, a refused input, a failed after-hook or a failed action. When
  the data source runs a transaction again, it runs only for the run that committed.
- Its receiver gives the `resource`, the `caller` and the call's `requestId` (`null` for a write no call made). The
  transaction is over, so there is no `data` to write through.
- **A failure never fails the write.** It is logged with the request id, the resource and the record's id, and the
  next after-commit hook still runs. It runs before the call is answered, so start slow work in a coroutine of your own.

On a typed model it gets the change as instances of the class:

```kotlin
hooks {
    afterCommit { change: Committed<Boat> ->
        when (change) {
            is Committed.Created -> board.show(change.record)
            is Committed.Updated -> if (change.before.out != change.record.out) board.move(change.record)
            is Committed.Deleted -> board.remove(change.before)
        }
    }
}
```

`afterCommit(model) { change -> }` does the same with a `Model` you hold. A module that must follow every resource's
writes uses a [`WriteExtension`](extending.md#taking-part-in-writes)'s `committed` instead; after-commit hooks run
after the extensions.
