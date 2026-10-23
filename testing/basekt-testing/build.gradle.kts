// testApplication fixtures and in-memory everything, for BaseKt's own tests and its users'.
plugins { id("basekt.jvm-library") }

dependencies {
    api(project(":server:basekt-data-memory"))
    api(libs.ktor.server.test.host)
    api(libs.kotlinx.coroutines.test)
}
