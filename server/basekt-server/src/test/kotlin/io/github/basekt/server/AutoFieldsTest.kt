package io.github.basekt.server

import io.github.basekt.data.DataSource
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** A made-up lab: numbered samples, stamped with when and by whom they were logged and last changed. */
private val labSchema: Schema = schema {
    resource("samples") {
        string("code") { required(); sequence("S-", digits = 5) }
        string("material") { required() }
        timestamps()
        string("loggedBy") { createdBy() }
        string("changedBy") { updatedBy() }
        permissions { read(authenticated); write(authenticated) }
    }
}

/** A made-up help desk: numbered tickets that only whoever opened one may change. */
private val helpDeskSchema: Schema = schema {
    resource("tickets") {
        long("number") { required(); sequence(start = 1000) }
        string("subject") { required() }
        string("openedBy") { createdBy() }
        permissions { read(authenticated); write(authenticated); ownedBy("openedBy", on = writes) }
    }
}

/** A clock the test moves by hand. */
private class TestClock(var now: Instant) : Clock {
    override fun now(): Instant = now
}

class AutoFieldsTest {
    private val start = Instant.parse("2026-10-23T09:00:00.123456Z")
    private val clock = TestClock(start)

    private fun app(schema: Schema, configure: BaseKtConfig.() -> Unit = {}, test: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                install(BaseKt) {
                    dataSource = InMemoryDataSource()
                    this.schema = schema
                    identities = headerIdentities
                    clock = this@AutoFieldsTest.clock
                    configure()
                }
            }
            test()
        }

    private suspend fun ApplicationTestBuilder.create(path: String, body: String, user: String = "ana"): JsonObject {
        val response = client.post(path) { caller(user); json(body) }
        assertEquals(HttpStatusCode.Created, response.status, response.json().toString())
        return response.json()
    }

    private fun JsonObject.text(field: String) = this[field]!!.jsonPrimitive.content

    @Test
    fun `a create sets the number, the times and the caller`() = app(labSchema) {
        val first = create("/api/samples", """{"material": "soil"}""")
        assertEquals("S-00001", first.text("code"))
        assertEquals("2026-10-23T09:00:00.123Z", first.text("createdAt"))
        assertEquals(first.text("createdAt"), first.text("updatedAt"))
        assertEquals("ana", first.text("loggedBy"))
        assertEquals("ana", first.text("changedBy"))
        assertEquals("S-00002", create("/api/samples", """{"material": "water"}""").text("code"))
    }

    @Test
    fun `an update moves the update time and caller, and keeps the rest`() = app(labSchema) {
        val created = create("/api/samples", """{"material": "soil"}""")
        clock.now = start + 5.minutes
        val response = client.patch("/api/samples/${created.text("id")}") { caller("ben"); json("""{"material": "clay"}""") }
        assertEquals(HttpStatusCode.OK, response.status)
        val updated = response.json()
        assertEquals("2026-10-23T09:05:00.123Z", updated.text("updatedAt"))
        assertEquals("ben", updated.text("changedBy"))
        assertEquals(created.text("createdAt"), updated.text("createdAt"))
        assertEquals("ana", updated.text("loggedBy"))
        assertEquals("S-00001", updated.text("code"))
    }

    @Test
    fun `clients can't set automatic fields, and a null is ignored`() = app(labSchema) {
        val forged = client.post("/api/samples") { caller("ana"); json("""{"material": "soil", "code": "S-99999", "loggedBy": "boss"}""") }
        assertEquals(HttpStatusCode.UnprocessableEntity, forged.status)
        val errors = forged.json()["fields"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("code", "loggedBy"), errors.map { it.text("field") })
        assertTrue(errors.all { it.text("code") == "read_only" })

        val blanks = create("/api/samples", """{"material": "soil", "code": null, "createdAt": null}""")
        assertEquals("S-00001", blanks.text("code"))
        val patch = client.patch("/api/samples/${blanks.text("id")}") { caller("ana"); json("""{"createdAt": "2020-01-01T00:00:00Z"}""") }
        assertEquals(HttpStatusCode.UnprocessableEntity, patch.status)
    }

    @Test
    fun `hooks see the values, and a vetoed create gives its number back`() {
        val seen = mutableListOf<Value?>()
        app(labSchema, configure = {
            hooks {
                beforeCreate("samples") { record ->
                    seen += record["code"]
                    if ((record["material"] as Value.Text).value == "mud") veto("material", "No mud.") else proceed(record)
                }
            }
        }) {
            assertEquals("S-00001", create("/api/samples", """{"material": "soil"}""").text("code"))
            assertEquals(HttpStatusCode.UnprocessableEntity, client.post("/api/samples") { caller("ana"); json("""{"material": "mud"}""") }.status)
            assertEquals("S-00002", create("/api/samples", """{"material": "sand"}""").text("code"))
            assertEquals<List<Value?>>(listOf("S-00001", "S-00002", "S-00002").map { Value.Text(it) }, seen)
        }
    }

    @Test
    fun `numbers start where the sequence says, and the creator owns the record`() = app(helpDeskSchema) {
        val first = create("/api/tickets", """{"subject": "Printer"}""", user = "ana")
        assertEquals(1000, first["number"]!!.jsonPrimitive.long)
        assertEquals("ana", first.text("openedBy"))
        assertEquals(1001, create("/api/tickets", """{"subject": "Wi-Fi"}""", user = "ben")["number"]!!.jsonPrimitive.long)

        val id = first.text("id")
        assertEquals(HttpStatusCode.OK, client.get("/api/tickets/$id") { caller("ben") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.patch("/api/tickets/$id") { caller("ben"); json("""{"subject": "Mine"}""") }.status)
        assertEquals(HttpStatusCode.OK, client.patch("/api/tickets/$id") { caller("ana"); json("""{"subject": "Printer, again"}""") }.status)
        val forged = client.post("/api/tickets") { caller("ben"); json("""{"subject": "Forged", "openedBy": "ana"}""") }
        assertEquals(HttpStatusCode.UnprocessableEntity, forged.status)
    }

    @Test
    fun `a sequence needs a data source that offers it`() {
        val plain = InMemoryDataSource().let { memory -> object : DataSource by memory {} }
        val error = assertFailsWith<IllegalArgumentException> { ResourceService(helpDeskSchema, plain) }
        assertTrue("tickets.number" in error.message!!, error.message)
    }
}
