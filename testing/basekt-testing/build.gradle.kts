// testApplication fixtures and in-memory everything, for BaseKt's own tests and its users'.
// It also holds the contract suites every data and storage adapter runs.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt testing: the DataSource and BlobStore contract suites every adapter runs, and testApplication fixtures."

dependencies {
    api(project(":server:basekt-data-memory"))
    api(project(":server:basekt-storage-memory"))
    api(project(":commerce:basekt-payment-memory"))
    api(libs.ktor.server.test.host)
    api(libs.kotlinx.coroutines.test)
    api(kotlin("test-junit5"))
}
