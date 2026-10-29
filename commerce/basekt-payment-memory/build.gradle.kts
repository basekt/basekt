// An in-memory PaymentProvider for tests, samples and prototypes, supporting every optional payment capability.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt in-memory payment provider: sessions, signed webhooks, simulated on-chain transfers, refunds and payouts for tests and prototypes."

dependencies {
    api(project(":commerce:basekt-payment"))
    testImplementation(project(":testing:basekt-testing"))
}
