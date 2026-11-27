// HitPay PaymentProvider over Ktor HttpClient (payment requests, refunds and Hitpay-Signature event webhooks).
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt HitPay payment provider: hosted payment requests, refunds and constant-time Hitpay-Signature webhook verification over Ktor HttpClient."

dependencies {
    api(project(":commerce:basekt-payment"))
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    testImplementation(project(":testing:basekt-testing"))
    testImplementation(libs.ktor.client.mock)
}
