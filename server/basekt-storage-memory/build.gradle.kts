// An in-memory BlobStore, for tests, samples and prototypes.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt in-memory BlobStore, for tests, samples and prototypes."

dependencies {
    api(project(":server:basekt-storage"))
    testImplementation(project(":testing:basekt-testing"))
}
