package io.github.basekt.server

import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.schema
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A made-up beehive log: inspections, and a hook that fails on a broken scale. */
private val hiveSchema = schema {
    resource("inspections") {
        string("hive") { required() }
        int("frames")
        permissions { read("keeper"); write("keeper") }
    }
}

/** A made-up tide table, readable by anyone. */
private val tideSchema = schema {
    resource("tides") {
        string("port") { required() }
        permissions { read(everyone) }
    }
}

class RequestIdsTest {
    @Test
    fun `every response carries a request id, and errors name it in their body`() = testApplication {
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = hiveSchema
                identities = headerIdentities
            }
            routing { get("/health") { call.respondText("ok") } }
        }
        val listed = client.get("/api/inspections") { asUser("keeper") }
        val first = assertNotNull(listed.headers["X-Request-Id"])
        // Every call of the application gets one, not only BaseKt's.
        val health = assertNotNull(client.get("/health").headers["X-Request-Id"])
        assertNotEquals(first, health)

        val refused = client.get("/api/inspections") { asUser("visitor") }
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertEquals(refused.headers["X-Request-Id"], refused.json()["requestId"]!!.jsonPrimitive.content)

        // A proxy's id is kept when it is a plain token, and replaced when it isn't.
        val passed = client.get("/api/inspections/none") { asUser("keeper"); header("X-Request-Id", "edge-42.a") }
        assertEquals("edge-42.a", passed.headers["X-Request-Id"])
        assertEquals("edge-42.a", passed.json()["requestId"]!!.jsonPrimitive.content)
        val odd = client.get("/api/inspections") { asUser("keeper"); header("X-Request-Id", "<script>") }
        assertNotEquals("<script>", odd.headers["X-Request-Id"])
    }

    @Test
    fun `an unexpected failure is a logged internal error that names only its request id`() = testApplication {
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = hiveSchema
                identities = headerIdentities
                hooks { beforeCreate("inspections") { _ -> error("The scale's driver crashed with secret 0xdead.") } }
            }
        }
        val response = client.postJson("/api/inspections", """{"hive": "h1", "frames": 8}""", "keeper")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = response.json()
        val id = response.headers["X-Request-Id"]!!
        assertEquals("internal_error", body["error"]!!.jsonPrimitive.content)
        assertEquals(id, body["requestId"]!!.jsonPrimitive.content)
        assertTrue(id in body["message"]!!.jsonPrimitive.content)
        assertFalse("secret" in body.toString())
    }

    @Test
    fun `the header can be renamed in the configuration`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig("basekt.requestIdHeader" to "X-Trace") }
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = tideSchema
            }
        }
        val response = client.get("/api/tides/nowhere")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(response.headers["X-Trace"], response.json()["requestId"]!!.jsonPrimitive.content)
        assertEquals(null, response.headers["X-Request-Id"])
    }
}
