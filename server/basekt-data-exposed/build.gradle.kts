// A DataSource over Exposed (JDBC): Postgres, MySQL, H2 and the rest.
plugins { id("basekt.jvm-library") }

dependencies {
    api(project(":server:basekt-data"))
    api(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    testImplementation(libs.h2)
}
