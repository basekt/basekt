// A DataSource over MongoDB: documents, filters, keyset pages, transactions, counters and conditional writes.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt DataSource over MongoDB: collections per resource, ObjectId ids and references, transactions and indexes."

dependencies {
    api(project(":server:basekt-data"))
    api(libs.mongodb.driver.kotlin.coroutine)
    testImplementation(project(":testing:basekt-testing"))
    testImplementation(libs.testcontainers)
    testImplementation(libs.embed.mongo)
}

// The tests run against a real MongoDB with transactions, so a replica set: the one -Pbasekt.mongo.url=mongodb://... names,
// else a Testcontainers container when Docker is available, else (with -Pbasekt.mongo.embedded=true) an embedded mongod
// that is downloaded once to ~/.embedmongo. -Pbasekt.mongo.required=true makes the tests fail instead of skipping when none
// of those is possible, as in CI.
tasks.test {
    // A failure says why, in CI logs too.
    testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    for (name in listOf("url", "embedded", "required")) {
        providers.gradleProperty("basekt.mongo.$name").orNull?.let { systemProperty("basekt.mongo.$name", it) }
    }
}
