// The DataSource SPI every adapter implements: queries, writes and transactions over a schema's resources.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt data SPI: the DataSource interface with transport-neutral queries, cursor pagination, batched loading and transactions."

dependencies {
    api(project(":core:basekt-schema"))
    api(libs.kotlinx.coroutines.core)
}
