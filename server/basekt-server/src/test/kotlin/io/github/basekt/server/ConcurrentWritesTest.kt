package io.github.basekt.server

import io.github.basekt.data.Comparison
import io.github.basekt.data.ConditionalUpdate
import io.github.basekt.data.ConditionalWrites
import io.github.basekt.data.DataSource
import io.github.basekt.data.Filter
import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import io.github.basekt.schema.schema
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A made-up wiki: pages count their edits, so an editor can't overwrite a change they haven't seen. */
private val wikiSchema: Schema = schema {
    resource("pages") {
        string("title") { required() }
        string("body")
        long("version") { version() }
        permissions { read(authenticated); write(authenticated) }
    }
}

/** A made-up event venue: sessions have a number of seats, and each booking takes one. */
private val venueSchema: Schema = schema {
    resource("sessions") {
        string("name") { required() }
        int("seatsLeft") { required(); min(0) }
        permissions { read(authenticated); write("organizer") }
    }
    resource("bookings") {
        reference("sessionId", "sessions") { required() }
        string("guest") { required() }
        permissions { read(authenticated); create(authenticated) }
    }
}

class ConcurrentWritesTest {
    private fun app(schema: Schema, configure: BaseKtConfig.() -> Unit = {}, test: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                install(BaseKt) {
                    dataSource = InMemoryDataSource()
                    this.schema = schema
                    identities = headerIdentities
                    configure()
                }
            }
            test()
        }

    private suspend fun ApplicationTestBuilder.edit(id: String, body: String, user: String) =
        client.patch("/api/pages/$id") { caller(user); json(body) }

    private fun JsonObject.version() = this["version"]!!.jsonPrimitive.long

    @Test
    fun `a page counts its edits`() = app(wikiSchema) {
        val created = client.post("/api/pages") { caller("ana"); json("""{"id": "home", "title": "Home"}""") }.json()
        assertEquals(1, created.version())
        val edited = edit("home", """{"body": "Welcome", "version": 1}""", "ana")
        assertEquals(HttpStatusCode.OK, edited.status)
        assertEquals(2, edited.json().version())
        // Without a version, the last write wins, and still counts.
        assertEquals(3, edit("home", """{"body": "Hello"}""", "ben").json().version())
    }

    @Test
    fun `an edit from an old version is refused, and keeps the newer change`() = app(wikiSchema) {
        client.post("/api/pages") { caller("ana"); json("""{"id": "home", "title": "Home"}""") }
        assertEquals(HttpStatusCode.OK, edit("home", """{"body": "Ana's text", "version": 1}""", "ana").status)

        val stale = edit("home", """{"body": "Ben's text", "version": 1}""", "ben")
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertTrue("version 2" in stale.json()["message"]!!.jsonPrimitive.content)
        val page = client.get("/api/pages/home") { caller("ana") }.json()
        assertEquals("Ana's text", page["body"]!!.jsonPrimitive.content)
        assertEquals(2, page.version())
    }

    @Test
    fun `concurrent edits from the same version let exactly one through`() = app(wikiSchema) {
        client.post("/api/pages") { caller("ana"); json("""{"id": "home", "title": "Home"}""") }
        val statuses = coroutineScope {
            List(10) { n -> async { edit("home", """{"body": "edit $n", "version": 1}""", "u$n").status } }.awaitAll()
        }
        assertEquals(1, statuses.count { it == HttpStatusCode.OK })
        assertEquals(9, statuses.count { it == HttpStatusCode.Conflict })
        assertEquals(2, client.get("/api/pages/home") { caller("ana") }.json().version())
    }

    @Test
    fun `clients can't set the version on create`() = app(wikiSchema) {
        val forged = client.post("/api/pages") { caller("ana"); json("""{"title": "Home", "version": 7}""") }
        assertEquals(HttpStatusCode.UnprocessableEntity, forged.status)
        assertEquals("read_only", forged.json()["fields"]!!.jsonArray.single().jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `concurrent bookings never take more seats than there are`() {
        val sessions = venueSchema.resource("sessions")!!
        val anyLeft = Filter.Compare("seatsLeft", Comparison.GTE, Value.Integer(1))
        app(venueSchema, configure = {
            hooks {
                beforeCreate("bookings") { booking ->
                    val session = (booking["sessionId"] as Value.Text).value
                    when ((data as ConditionalWrites).updateIf(sessions, session, anyLeft, increments = mapOf("seatsLeft" to Value.Integer(-1)))) {
                        is ConditionalUpdate.Updated -> proceed(booking)
                        else -> veto("sessionId", "Sold out.")
                    }
                }
            }
        }) {
            client.post("/api/sessions") { caller("org", listOf("organizer")); json("""{"id": "s1", "name": "Keynote", "seatsLeft": 10}""") }
            val statuses = coroutineScope {
                List(25) { n ->
                    async { client.post("/api/bookings") { caller("g$n"); json("""{"sessionId": "s1", "guest": "Guest $n"}""") }.status }
                }.awaitAll()
            }
            assertEquals(10, statuses.count { it == HttpStatusCode.Created })
            assertEquals(15, statuses.count { it == HttpStatusCode.UnprocessableEntity })
            val left = client.get("/api/sessions/s1") { caller("org") }.json()["seatsLeft"]!!.jsonPrimitive.long
            assertEquals(0, left)
            assertEquals(10, client.get("/api/bookings?limit=100") { caller("org") }.json()["items"]!!.jsonArray.size)
        }
    }

    @Test
    fun `a version needs a data source that offers conditional writes`() {
        val plain = InMemoryDataSource().let { memory -> object : DataSource by memory {} }
        val error = assertFailsWith<IllegalArgumentException> { ResourceService(wikiSchema, plain) }
        assertTrue("pages.version" in error.message!!, error.message)
    }
}
