# Automatic fields

**Automatic fields** are set by the server, never the client: when and by whom a record was created and last
changed, and readable numbers for documents.

```kotlin
resource("requests") {
    string("ref") { sequence("REQ-", digits = 6) }   // REQ-000001, REQ-000002...
    timestamps()                                      // createdAt and updatedAt
    string("createdBy") { createdBy() }               // the caller's id; also updatedBy()
    permissions { read(authenticated); write(authenticated); ownedBy("createdBy", on = writes) }
}
```

They're filled in the write's transaction before the before-hooks, so [hooks](hooks.md) see them. A client that sends
one gets `read_only`, and a `null` is ignored.

## Sequences

Numbers come from the data source's `Sequencing` capability: they never repeat, and a write that fails gives its
number back, so there are no gaps. The in-memory and Exposed adapters offer it. The Exposed adapter keeps counters in
a `basekt_sequences` table.

## Times in tests

Set `clock` in `install(BaseKt)` to control the times in tests.
