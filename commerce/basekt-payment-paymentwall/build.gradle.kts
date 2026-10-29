// Paymentwall Digital Goods & Subscriptions PaymentProvider with Signature v3 widget signing and IP-allowlisted pingbacks.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt Paymentwall payment provider: Signature v3 Digital Goods and subscription checkout URLs, IP-allowlisted pingback verification and plain-text OK responses."

dependencies {
    api(project(":commerce:basekt-payment"))
    implementation(libs.ktor.http)
    testImplementation(project(":testing:basekt-testing"))
}
