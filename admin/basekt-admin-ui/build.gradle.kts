// install(BaseKtAdminUi): a schema-driven admin panel (React, shadcn/ui and Tailwind) for the JSON admin API, prebuilt
// into the jar and themed from Kotlin.
//
// The panel's source is in web/. Node runs here, in BaseKt's own build, and never for users of the jar: npmBuild
// builds web/dist, and processResources copies it into the jar under basekt-admin-ui/.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt admin UI: install(BaseKtAdminUi), a schema-driven admin panel served from the jar, with a typed theme."

dependencies {
    api(libs.ktor.server.core)
    implementation(libs.ktor.server.html.builder)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.ktor.server.test.host)
}

val web = layout.projectDirectory.dir("web")
val npm = if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm"

// The panel compiles against the admin API's generated wire types, so a change there rebuilds and rechecks it.
val wireTypes = rootProject.layout.projectDirectory.file("admin/basekt-admin-api/typescript/basekt-admin-api.ts")

val webSources = files(
    web.dir("src"),
    web.file("index.html"),
    web.file("package.json"),
    web.file("package-lock.json"),
    web.file("tsconfig.json"),
    web.file("vite.config.ts"),
    wireTypes,
)

val npmCi by tasks.registering(Exec::class) {
    group = "build"
    description = "Installs the panel's locked npm dependencies."
    workingDir(web)
    commandLine(npm, "ci", "--no-audit", "--no-fund")
    inputs.files(web.file("package.json"), web.file("package-lock.json"))
    outputs.file(web.file("node_modules/.package-lock.json"))
}

val npmBuild by tasks.registering(Exec::class) {
    group = "build"
    description = "Type-checks the panel and builds it into web/dist."
    dependsOn(npmCi)
    workingDir(web)
    commandLine(npm, "run", "build")
    inputs.files(webSources)
    outputs.dir(web.dir("dist"))
}

val npmTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the panel's Vitest tests."
    dependsOn(npmCi)
    workingDir(web)
    commandLine(npm, "test")
    inputs.files(webSources)
    val marker = layout.buildDirectory.file("npm-test/passed")
    outputs.file(marker)
    doLast { marker.get().asFile.writeText("passed\n") }
}

tasks.processResources { from(npmBuild) { into("basekt-admin-ui") } }
tasks.check { dependsOn(npmTest) }
tasks.clean { delete(web.dir("dist")) }
