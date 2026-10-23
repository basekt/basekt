// A small shop backend on BaseKt: categories, products, customers and orders, on Exposed and H2, with JWT and
// bearer auth, a pricing hook and the JSON admin API.
plugins {
    id("basekt.jvm-library")
    application
}

application { mainClass.set("io.github.basekt.samples.shop.ShopKt") }

dependencies {
    implementation(project(":server:basekt-server"))
    implementation(project(":server:basekt-data-exposed"))
    implementation(project(":server:basekt-auth"))
    implementation(project(":admin:basekt-admin-api"))
    implementation(libs.ktor.server.cio)
    runtimeOnly(libs.h2)
    runtimeOnly(libs.logback.classic)
    testImplementation(project(":testing:basekt-testing"))
}
