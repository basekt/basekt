// The PaymentProvider SPI every payment adapter implements: sessions, webhooks, on-chain receipts, refunds and payouts.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt payment SPI: PaymentProvider, exact multi-decimal amounts, and optional capabilities for webhooks, on-chain receipts, refunds and payouts."

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
}
