// A small shop backend on BaseKt: categories, products, customers and orders, on Exposed and H2, with JWT and
// bearer auth, a pricing hook, product images on local disk, the JSON admin API and the admin panel.
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
    implementation(project(":admin:basekt-admin-ui"))
    implementation(project(":server:basekt-files"))
    implementation(project(":server:basekt-storage-local"))
    implementation(libs.ktor.server.cio)
    runtimeOnly(libs.h2)
    runtimeOnly(libs.logback.classic)
    testImplementation(project(":testing:basekt-testing"))
}
