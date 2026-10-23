import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// A BaseKt multiplatform library: shared by the server, the client SDK and the admin UI.
// JVM now; wasmJs joins when the admin UI and client SDK do.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

group = providers.gradleProperty("basekt.group").get()
version = providers.gradleProperty("basekt.version").get()

kotlin {
    explicitApi()
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
