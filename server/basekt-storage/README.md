# basekt-storage

The `BlobStore` SPI: streamed puts and reads of bytes under validated keys, byte ranges, and optional listing and presigned URLs. `basekt-files` keeps its buckets over any store.

```kotlin
dependencies { implementation("io.github.basekt:basekt-storage:0.2.0") }
```

## Use

```kotlin
val key = BlobKey.of("images/seed/lamp.png")
store.put(key, Buffer().apply { write(bytes) }, "image/png")
```

## Good to know

- Adapters: `basekt-storage-memory`, `basekt-storage-local` and `basekt-storage-s3`.
- To write one, run `BlobStoreContract` from `basekt-testing`.

## Documentation

- [Adapters](https://basekt.github.io/basekt/adapters/)
- [Files and storage](https://basekt.github.io/basekt/files/)
- [All modules](../../README.md#modules)
