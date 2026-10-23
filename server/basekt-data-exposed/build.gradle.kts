// A DataSource over Exposed (JDBC): Postgres, MySQL, H2 and the rest.
plugins { id("basekt.jvm-library") }

dependencies {
    api(project(":server:basekt-data"))
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    testImplementation(project(":testing:basekt-testing"))
    testImplementation(libs.h2)
    testImplementation(libs.postgresql)
}

// Tests run on H2. Pass -Pbasekt.postgres.url=jdbc:postgresql://... (and .user, .password) to also run the contract
// suite on Postgres.
tasks.test {
    for (name in listOf("url", "user", "password")) {
        providers.gradleProperty("basekt.postgres.$name").orNull?.let { systemProperty("basekt.postgres.$name", it) }
    }
}
