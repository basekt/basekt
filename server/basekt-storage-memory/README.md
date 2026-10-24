# basekt-storage-memory

An in-memory `BlobStore`, for tests, samples and prototypes.

```kotlin
dependencies { implementation("io.github.basekt:basekt-storage-memory:0.2.0") }
```

Builds on: basekt-storage.

## Use

```kotlin
val store = InMemoryBlobStore()
```

## Good to know

- Nothing is kept after the process ends.

## Documentation

- [Adapters](https://basekt.github.io/basekt/adapters/)
- [All modules](../../README.md#modules)
