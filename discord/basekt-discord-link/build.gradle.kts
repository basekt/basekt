// The Discord account-link SPI and AccountLinker: a one-time code made by your site, redeemed by the Discord user.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt Discord link: AccountLinker and the PendingLinkStore and LinkedAccounts SPIs that tie an account to a Discord user with a one-time code."

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(project(":discord:basekt-discord-link-memory"))
    testImplementation(project(":testing:basekt-testing"))
}
