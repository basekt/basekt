# basekt-files

`install(BaseKtFiles)`: buckets with limits and permissions, streamed uploads, downloads with byte ranges and safe headers, and `file` fields kept in step with their files.

```kotlin
dependencies { implementation("io.github.basekt:basekt-files:0.2.0") }
```

Builds on: basekt-server, basekt-storage.

## Use

```kotlin
install(BaseKtFiles) {
    store = LocalBlobStore(Path.of("uploads"))
    bucket("images") {
        maxBytes = 5 * MEGABYTE
        accept("image/png", "image/jpeg", "image/webp")
        read(everyone)
        write("staff")
    }
}
// in the schema: file("image", "images")
```

## Good to know

- An upload's type comes from its bytes, never from what the client claims.
- A file no record points to is deleted, and uploads nobody claimed are swept after a day: keep a picture used in a text as a record with a file field.

## Documentation

- [Files and storage](https://basekt.github.io/basekt/files/)
- [All modules](../../README.md#modules)
