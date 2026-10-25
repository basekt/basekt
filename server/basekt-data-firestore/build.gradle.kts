// A DataSource over Cloud Firestore: documents, filters, keyset pages, transactions, counters and conditional writes.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt DataSource over Cloud Firestore: a collection per resource, document ids as record ids, transactions and aggregation counts."

dependencies {
    api(project(":server:basekt-data"))
    api(libs.google.cloud.firestore)
    testImplementation(project(":testing:basekt-testing"))
    testImplementation(libs.testcontainers.gcloud)
}

// The tests run against the Firestore emulator: the one -Pbasekt.firestore.emulator=host:port names, else a Testcontainers
// container of the Cloud SDK's emulator image when Docker is available. -Pbasekt.firestore.required=true makes the tests
// fail instead of skipping when neither is possible, as in CI.
tasks.test {
    // A failure says why, in CI logs too.
    testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    for (name in listOf("emulator", "required")) {
        providers.gradleProperty("basekt.firestore.$name").orNull?.let { systemProperty("basekt.firestore.$name", it) }
    }
}
