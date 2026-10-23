// The BlobStore SPI every storage adapter implements: streamed puts and reads of bytes under validated keys.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt storage SPI: the BlobStore interface for files, with streamed reads and writes, ranges and optional capabilities."

dependencies {
    api(libs.kotlinx.io.core)
    api(libs.kotlinx.coroutines.core)
}
