// A small lessons site on BaseKt: Markdown fields where only one allows formulas, images kept in storage, pages that
// load KaTeX only when a lesson has a formula, and the admin panel with its visual Markdown editor.
plugins {
    id("basekt.jvm-library")
    application
}

application { mainClass.set("io.github.basekt.samples.lessons.LessonsKt") }

dependencies {
    implementation(project(":server:basekt-server"))
    implementation(project(":server:basekt-data-memory"))
    implementation(project(":server:basekt-files"))
    implementation(project(":server:basekt-storage-memory"))
    implementation(project(":server:basekt-richtext"))
    implementation(project(":admin:basekt-admin-api"))
    implementation(project(":admin:basekt-admin-ui"))
    implementation(libs.ktor.server.cio)
    runtimeOnly(libs.logback.classic)
    testImplementation(project(":testing:basekt-testing"))
}
