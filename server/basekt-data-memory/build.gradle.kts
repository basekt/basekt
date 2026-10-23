// An in-memory DataSource, for tests, samples and prototypes.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt in-memory DataSource, for tests, samples and prototypes."

dependencies {
    api(project(":server:basekt-data"))
    testImplementation(project(":testing:basekt-testing"))
}
