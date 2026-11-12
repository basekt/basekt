// A ChannelSender that posts to Discord channel webhooks, with no bot and no gateway connection.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt Discord webhook sender: a ChannelSender that posts notifications to named Discord channel webhooks, without mentions unless a route asks for one."

dependencies {
    api(project(":server:basekt-notify"))
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":testing:basekt-testing"))
    testImplementation(libs.ktor.client.mock)
}
