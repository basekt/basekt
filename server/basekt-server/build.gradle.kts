// install(BaseKt): the resource registry, the schema-driven REST API and lifecycle, as a Ktor plugin.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt server: install(BaseKt), a schema-driven REST API for Ktor with permissions, hooks and typed errors."

dependencies {
    api(project(":server:basekt-data"))
    api(libs.ktor.server.core)
    testImplementation(project(":testing:basekt-testing"))
}
