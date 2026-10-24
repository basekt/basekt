// Pages and a public API generated from a schema's `storefront { }` blocks, for browsing a catalog without signing in.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt storefront: listing and detail pages and a public API generated from the schema."

dependencies {
    api(project(":server:basekt-server"))
    testImplementation(project(":testing:basekt-testing"))
}
