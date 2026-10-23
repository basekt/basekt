// Resources, fields, relations, validation and permissions as data. No Ktor and no database.
plugins {
    id("basekt.kmp-library")
    id("basekt.publish")
}

description = "BaseKt schema: resources, typed fields, relations, validation and permissions as data, with a Kotlin DSL, JSON form and typed models."

kotlin {
    sourceSets {
        commonMain.dependencies { api(libs.kotlinx.serialization.json) }
    }
}
