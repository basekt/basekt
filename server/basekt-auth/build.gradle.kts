// Who may do what: adapters onto Ktor Authentication (JWT, bearer) and roles mapped to the schema's permissions.
plugins { id("basekt.jvm-library") }

dependencies {
    api(project(":server:basekt-server"))
    api(libs.ktor.server.auth)
    api(libs.ktor.server.auth.jwt)
    testImplementation(project(":testing:basekt-testing"))
}
