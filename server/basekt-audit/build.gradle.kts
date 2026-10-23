// install(BaseKtAudit): a log of who changed what, for every write and action, in the write's own transaction.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt audit: install(BaseKtAudit), a log of every create, update, delete and action, with who made it and what changed."

dependencies {
    api(project(":server:basekt-server"))
    testImplementation(project(":testing:basekt-testing"))
}
