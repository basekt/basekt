# basekt-storage-local

A `BlobStore` over a local directory: files under one directory, written atomically.

```kotlin
dependencies { implementation("io.github.basekt:basekt-storage-local:0.2.0") }
```

Builds on: basekt-storage.

## Use

```kotlin
val store = LocalBlobStore(Path.of("uploads"))
```

## Good to know

- Keys are validated, so a key can never name a path outside the directory.

## Documentation

- [Adapters](https://basekt.github.io/basekt/adapters/)
- [All modules](../../README.md#modules)
