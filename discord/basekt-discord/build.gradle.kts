// install(BaseKtDiscord): a Discord bot as a Ktor plugin, with slash commands, a per-user rate limit and a clean shutdown.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt Discord bot: install(BaseKtDiscord), slash commands with a per-user rate limit, started and stopped with the Ktor application."

dependencies {
    api(libs.ktor.server.core)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kord.core)
    testImplementation(project(":testing:basekt-testing"))
}
