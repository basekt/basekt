import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// A BaseKt JVM library: Kotlin with explicit API, Java 17 bytecode, JUnit tests.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    `java-library`
}

group = providers.gradleProperty("basekt.group").get()
version = providers.gradleProperty("basekt.version").get()

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    explicitApi()
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    "testImplementation"(kotlin("test"))
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
