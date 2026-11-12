// In-memory PendingLinkStore and LinkedAccounts for tests, samples and prototypes.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt in-memory Discord link stores for tests, samples and prototypes; they pass the PendingLinkStore and LinkedAccounts contracts."

dependencies {
    api(project(":discord:basekt-discord-link"))
    testImplementation(project(":testing:basekt-testing"))
}
