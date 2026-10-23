// install(BaseKt): the resource registry, the schema-driven REST API and lifecycle, as a Ktor plugin.
plugins { id("basekt.jvm-library") }

dependencies {
    api(project(":server:basekt-data"))
    api(libs.ktor.server.core)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(project(":testing:basekt-testing"))
}
