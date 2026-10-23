// A DataSource over Exposed (JDBC): Postgres, MySQL, H2 and the rest.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt DataSource over Exposed/JDBC: Postgres, MySQL and H2."

dependencies {
    api(project(":server:basekt-data"))
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    testImplementation(project(":testing:basekt-testing"))
    testImplementation(libs.h2)
    testImplementation(libs.postgresql)
    testImplementation(libs.testcontainers.postgresql)
}

// Tests run on H2, and the contract suite on Postgres too: in a Testcontainers container when Docker is available,
// or on the database -Pbasekt.postgres.url=jdbc:postgresql://... (and .user, .password) names.
// -Pbasekt.postgres.required=true makes the Postgres tests fail instead of skipping when there's no Docker, as in CI.
tasks.test {
    for (name in listOf("url", "user", "password", "required")) {
        providers.gradleProperty("basekt.postgres.$name").orNull?.let { systemProperty("basekt.postgres.$name", it) }
    }
}
