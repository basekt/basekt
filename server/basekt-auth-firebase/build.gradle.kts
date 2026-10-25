// Firebase ID tokens as an identity: a Ktor JWT provider that checks them against Google's rotating public keys.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt Firebase auth: a Ktor provider for Firebase ID tokens, verified against Google's public keys without the Admin SDK."

dependencies {
    api(project(":server:basekt-auth"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":testing:basekt-testing"))
}
