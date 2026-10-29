// Supabase Auth tokens as an identity: a Ktor provider that verifies them via GoTrue or HMAC/JWT secret.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt Supabase auth: a Ktor provider for Supabase access tokens, verified via GoTrue or JWT secret."

dependencies {
    api(project(":server:basekt-auth"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":testing:basekt-testing"))
}
