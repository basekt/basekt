// Multiplatform Firebase Auth (Identity Toolkit + Secure Token) client adapter.
plugins {
    id("basekt.kmp-library")
    id("basekt.publish")
}

description = "BaseKt Firebase Auth client: multiplatform Identity Toolkit sign-in, sign-up, Secure Token refresh, and password reset over Ktor HttpClient."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":client:basekt-auth-client"))
            api(libs.ktor.client.core)
            implementation(libs.ktor.http)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(project(":server:basekt-auth"))
            implementation(project(":server:basekt-auth-firebase"))
            implementation(project(":server:basekt-server"))
            implementation(project(":server:basekt-data-memory"))
            implementation(libs.ktor.server.test.host)
            implementation(libs.ktor.server.auth.jwt)
        }
    }
}
