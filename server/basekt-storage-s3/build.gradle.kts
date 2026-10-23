// A BlobStore on S3 or any S3-compatible service (MinIO, Cloudflare R2, ...), with no SDK: Signature V4 over the
// JDK's HttpClient.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt S3 BlobStore: S3, MinIO, R2 and other S3-compatible stores, with multipart uploads, ranges and presigned URLs."

dependencies {
    api(project(":server:basekt-storage"))
    testImplementation(project(":testing:basekt-testing"))
    testImplementation(libs.ktor.server.cio)
}

// The contract suite also runs against a real S3-compatible store when given one:
//   -Pbasekt.s3.endpoint=http://localhost:9000 -Pbasekt.s3.bucket=basekt -Pbasekt.s3.region=us-east-1
//   -Pbasekt.s3.accessKey=... -Pbasekt.s3.secretKey=...
tasks.test {
    for (name in listOf("endpoint", "bucket", "region", "accessKey", "secretKey")) {
        providers.gradleProperty("basekt.s3.$name").orNull?.let { systemProperty("basekt.s3.$name", it) }
    }
}
