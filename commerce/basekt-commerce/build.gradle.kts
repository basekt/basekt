// Optional umbrella for selling online: depend on this to get every commerce module at once, or on one module alone.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt commerce: an optional umbrella over the modules for selling online."

dependencies {
    api(project(":commerce:basekt-storefront"))
}
