# basekt-storage-s3

A `BlobStore` for S3, MinIO, R2 and other S3-compatible services: Signature V4 with no SDK, multipart uploads, byte ranges and presigned URLs.

```kotlin
dependencies { implementation("io.github.basekt:basekt-storage-s3:0.2.0") }
```

Builds on: basekt-storage.

## Use

```kotlin
val store = S3BlobStore(S3Settings(endpoint, bucket, region, accessKey, secretKey))
```

## Good to know

- Its contract suite runs against an in-process fake that checks every request signature, and optionally against a real bucket.

## Documentation

- [Adapters](https://basekt.github.io/basekt/adapters/)
- [All modules](../../README.md#modules)
