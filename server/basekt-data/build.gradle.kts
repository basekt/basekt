// The DataSource SPI every adapter implements: queries, writes and transactions over a schema's resources.
plugins { id("basekt.jvm-library") }

dependencies {
    api(project(":core:basekt-schema"))
    api(libs.kotlinx.coroutines.core)
}
