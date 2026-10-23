// install(BaseKtAdmin): a JSON admin API generated from the schema (list, search, detail, create, update, delete,
// bulk delete, CSV export), and the TypeScript types of its wire format for admin panels.
plugins { id("basekt.jvm-library") }

dependencies {
    api(project(":server:basekt-server"))
    testImplementation(project(":testing:basekt-testing"))
    testImplementation(project(":server:basekt-auth"))
}

// The TypeScript types are generated from the wire classes and checked in. TypeScriptTypesTest fails the build
// when they're out of date.
val generateTypeScript by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Writes typescript/basekt-admin-api.ts from the admin API's @Serializable classes."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.basekt.admin.typescript.TypeScriptTypesKt")
    args(layout.projectDirectory.file("typescript/basekt-admin-api.ts").asFile.absolutePath)
}

tasks.test { inputs.dir(layout.projectDirectory.dir("typescript")).optional() }
