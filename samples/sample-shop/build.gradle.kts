// A small shop backend on BaseKt: categories, products, customers and orders, on Exposed and H2, with JWT and
// bearer auth, a pricing hook, product images on local disk, the JSON admin API, the admin panel and an OpenAPI
// document.
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
    implementation(project(":server:basekt-openapi"))
    implementation(libs.ktor.server.cio)
    runtimeOnly(libs.h2)
    runtimeOnly(libs.logback.classic)
    testImplementation(project(":testing:basekt-testing"))
}

// The OpenAPI document is checked in at openapi/openapi.json, where a TypeScript client is generated from it in CI.
// OpenApiFileTest fails the build when it is out of date.
val generateOpenApi by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Writes openapi/openapi.json from the document the shop serves."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.basekt.samples.shop.OpenApiFileKt")
    args(layout.projectDirectory.file("openapi/openapi.json").asFile.absolutePath)
}

tasks.test { inputs.file(layout.projectDirectory.file("openapi/openapi.json")).optional() }
