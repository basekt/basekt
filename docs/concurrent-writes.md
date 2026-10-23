# Concurrent writes

**Concurrent writes** are safe in two ways.

## Versions

A `version()` field counts a record's writes. An update that sends the version it read is refused with
`409 conflict` if someone else saved in between, instead of silently overwriting their change. The admin panel sends
it on every edit.

```kotlin
resource("pages") {
    string("body")
    long("version") { version() }   // 1 on create, +1 on every update; PATCH {"body": "...", "version": 3}
}
```

## Conditional writes

For numbers many writers change at once, such as what's left of something, the data source's `ConditionalWrites`
capability updates a record only if it still matches a condition, and adds to number fields, in one atomic step. A
hook takes one only if any are left, and concurrent writers can't take more than there is:

```kotlin
when ((data as ConditionalWrites).updateIf(pools, poolId, Filter.Compare("left", Comparison.GTE, Value.Integer(1)),
    increments = mapOf("left" to Value.Integer(-1)))) {
    is ConditionalUpdate.Updated -> proceed(claim)
    else -> veto("poolId", "None left.")
}
```

The in-memory and Exposed adapters offer it. The Exposed adapter does it as one `UPDATE … WHERE`, which the database
checks again under the row's lock.
