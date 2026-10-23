package io.github.basekt.samples.shop

import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/** Starts the sample. BaseKt's plugins are installed here as each one lands (v0.1.0 roadmap). */
public fun main() {
    embeddedServer(CIO, port = 8080) {
        routing { get("/health") { call.respondText("ok") } }
    }.start(wait = true)
}
