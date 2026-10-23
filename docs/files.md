# Files and storage

**Files** (`basekt-files`) are kept in buckets over any `BlobStore`: in memory, on local disk, or on S3 with
`S3BlobStore(S3Settings(endpoint, bucket, region, accessKey, secretKey))` from `basekt-storage-s3`. The stores are
listed under [Adapters](adapters.md#storage).

```kotlin
install(BaseKtFiles) {
    store = LocalBlobStore(Path.of("uploads"))                     // basekt-storage-local
    bucket("images") {
        maxBytes = 5 * MEGABYTE
        accept("image/png", "image/jpeg", "image/webp")
        read(everyone)
        write("staff")
    }
}
```

## Uploads and downloads

`POST /files/images` takes a `multipart/form-data` upload and answers `{key, bucket, name, size, contentType, url}`.
`GET /files/{key}` serves it with byte ranges and ETags.

An upload's type comes from its bytes, never from what the client claims, and it is stopped as soon as it passes the
bucket's limit. Downloads send `nosniff` and a sandboxing CSP, and only images, audio, video, PDF and plain text are
shown inline. HTML, SVG and everything else download as attachments.

## File fields

A **file field** points a record at an uploaded file: `file("image", "images")`, or `file(Product::image, "images")`
on a [typed model](schema.md#typed-models). Its value is the file's key. A write may only point at a file that exists
in that bucket.

When a record stops pointing to a file and no other record points to it, the file is deleted after the write commits.
Uploads that no record ever points to are swept after a day. The admin panel uploads, previews and replaces files in
these fields.
