// Multiplatform client authentication SPI, session coordinator, and OAuth 2.0 PKCE flows.
plugins {
    id("basekt.kmp-library")
    id("basekt.publish")
}

description = "BaseKt client auth SPI: AuthClient, OAuthCapable, SessionManager, RFC 7636 PKCE (S256), and multiplatform OAuth redirect flows."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            implementation(libs.ktor.http)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
