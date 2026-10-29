// Lemon Squeezy Merchant-of-Record PaymentProvider over Ktor HttpClient (JSON:API checkouts, portal URLs and HMAC-SHA256 webhooks).
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt Lemon Squeezy payment provider: JSON:API checkouts, subscription customer portal lookup and constant-time X-Signature webhook verification over Ktor HttpClient."

dependencies {
    api(project(":commerce:basekt-payment"))
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    testImplementation(project(":testing:basekt-testing"))
    testImplementation(libs.ktor.client.mock)
}
