// install(BaseKtOpenApi): an OpenAPI 3.1 document of BaseKt's REST API, generated from the schema, for clients in
// other languages to generate typed clients from.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt OpenAPI: install(BaseKtOpenApi), an OpenAPI 3.1 document of the REST API generated from the schema."

dependencies {
    api(project(":server:basekt-server"))
    testImplementation(project(":testing:basekt-testing"))
    testImplementation(libs.json.schema.validator)
}
