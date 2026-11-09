// Multiplatform Supabase Auth (GoTrue) client adapter: email/password, refresh, recovery, and OAuth 2.0 PKCE.
plugins {
    id("basekt.kmp-library")
    id("basekt.publish")
}

description = "BaseKt Supabase Auth client: multiplatform GoTrue sign-in, sign-up, token refresh, password recovery, and OAuth 2.0 PKCE over Ktor HttpClient."

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
            implementation(project(":server:basekt-auth-supabase"))
            implementation(project(":server:basekt-server"))
            implementation(project(":server:basekt-data-memory"))
            implementation(libs.ktor.server.test.host)
            implementation(libs.ktor.server.auth.jwt)
        }
    }
}
