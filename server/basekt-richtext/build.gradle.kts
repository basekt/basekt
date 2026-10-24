// install(BaseKtRichText): checks the Markdown fields of a schema on every write, and renders them to safe HTML and plain text.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt rich text: install(BaseKtRichText) checks Markdown fields, with TeX formulas, on every write, and renders them to safe HTML and plain text."

dependencies {
    api(project(":server:basekt-server"))
    api(libs.markdown)
    testImplementation(project(":testing:basekt-testing"))
}
