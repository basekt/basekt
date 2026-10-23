// Resources, fields, relations, validation and permissions as data. No Ktor and no database.
plugins { id("basekt.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies { api(libs.kotlinx.serialization.json) }
    }
}
