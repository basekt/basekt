// A BlobStore on the local filesystem.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt local-filesystem BlobStore: files under one directory, written atomically."

dependencies {
    api(project(":server:basekt-storage"))
    testImplementation(project(":testing:basekt-testing"))
}
