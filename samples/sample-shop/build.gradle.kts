// A small shop backend on BaseKt: products and orders, an admin, in-memory or H2.
plugins {
    id("basekt.jvm-library")
    application
}

application { mainClass.set("io.github.basekt.samples.shop.ShopKt") }

dependencies {
    implementation(project(":admin:basekt-admin-api"))
    implementation(project(":server:basekt-data-memory"))
    implementation(libs.ktor.server.cio)
    runtimeOnly(libs.logback.classic)
    testImplementation(project(":testing:basekt-testing"))
}
