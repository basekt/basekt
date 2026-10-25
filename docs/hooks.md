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
