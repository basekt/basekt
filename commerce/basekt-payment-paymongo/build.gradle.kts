// PayMongo PaymentProvider adapter over io.github.ronjunevaldoz:paymongo-kotlin:1.3.0, with no Ktor server dependency.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt PayMongo payment provider: Checkout Sessions, Payment Links, Refunds and Paymongo-Signature webhook verification."

dependencies {
    api(project(":commerce:basekt-payment"))
    api(libs.paymongo.kotlin.jvm)
    testImplementation(project(":testing:basekt-testing"))
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.serialization.kotlinx.json)
}
