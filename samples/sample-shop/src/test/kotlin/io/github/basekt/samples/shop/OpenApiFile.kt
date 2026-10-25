package io.github.basekt.samples.shop

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.mergeWith
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** The checked-in OpenAPI document, relative to this module. A TypeScript client is generated from it in CI. */
const val OPENAPI_FILE: String = "openapi/openapi.json"

/** Starts the shop as `application.conf` configures it, on a fresh H2 database, and returns the document it serves. */
fun servedOpenApi(): String {
    var document = ""
    testApplication {
        environment {
            config = ApplicationConfig("application.conf").mergeWith(
                MapApplicationConfig(
                    "shop.database.url" to "jdbc:h2:mem:openapi-${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
                    "shop.files.dir" to Files.createTempDirectory("shop-files").toString(),
                ),
            )
        }
        document = client.get("/openapi.json").bodyAsText()
    }
    return document
}

/** Writes the served document to the file named by the only argument. `./gradlew :samples:sample-shop:generateOpenApi` runs it. */
fun main(args: Array<String>) {
    val file = File(args.single())
    file.parentFile.mkdirs()
    file.writeText(servedOpenApi())
}
