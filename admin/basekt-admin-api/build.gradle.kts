// install(BaseKtAdmin): admin endpoints generated from the schema (list, detail, create, update, delete, actions, export).
plugins { id("basekt.jvm-library") }

dependencies {
    api(project(":server:basekt-server"))
    api(project(":server:basekt-auth"))
    testImplementation(project(":testing:basekt-testing"))
}
