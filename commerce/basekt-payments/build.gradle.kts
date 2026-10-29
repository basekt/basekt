// install(BaseKtPayments): checkout sessions, verified webhooks, on-chain confirmations, atomic replay protection and payouts.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt payments: install(BaseKtPayments), multi-provider checkout, webhook and on-chain verification, atomic replay protection, refunds and payouts."

dependencies {
    api(project(":server:basekt-server"))
    api(project(":commerce:basekt-payment"))
    testImplementation(project(":testing:basekt-testing"))
    testImplementation(project(":server:basekt-data-exposed"))
    testImplementation(project(":commerce:basekt-payment-paymongo"))
    testImplementation(project(":commerce:basekt-payment-paymentwall"))
    testImplementation(project(":commerce:basekt-payment-lemonsqueezy"))
    testImplementation(project(":commerce:basekt-payment-kaia"))
    testImplementation(libs.h2)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.serialization.kotlinx.json)
}
