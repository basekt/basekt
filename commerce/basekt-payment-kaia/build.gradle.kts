// Kaia KIP-7/ERC-20 (USDT) PaymentProvider and PayoutCapable adapter over Ktor HttpClient + JSON-RPC 2.0.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt Kaia payment provider: Ktor HttpClient JSON-RPC verification of KIP-7/ERC-20 (USDT) transfers, log scanning and gas-checked payouts."

dependencies {
    api(project(":commerce:basekt-payment"))
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    testImplementation(project(":testing:basekt-testing"))
    testImplementation(libs.ktor.client.mock)
}
