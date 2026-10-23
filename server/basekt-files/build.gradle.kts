// install(BaseKtFiles): buckets of files over any BlobStore, with uploads, downloads and permissions over HTTP.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt files: install(BaseKtFiles), buckets with limits and permissions, streamed uploads and safe downloads over any BlobStore."

dependencies {
    api(project(":server:basekt-server"))
    api(project(":server:basekt-storage"))
    testImplementation(project(":testing:basekt-testing"))
}
