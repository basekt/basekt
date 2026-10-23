package io.github.basekt.audit

import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import io.github.basekt.schema.schema
import io.github.basekt.server.BaseKt
import io.github.basekt.server.BaseKtConfig
import io.github.basekt.server.Identity
import io.github.basekt.server.IdentityResolver
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** A made-up plant nursery: plants with a price and a supplier code that the log must not show. */
private val nurserySchema: Schema = schema {
    auditLog("audit", readers = setOf("manager"))
    resource("plants") {
        string("name") { required() }
        decimal("price")
        string("supplierCode")
        permissions { read(authenticated); write("gardener") }
    }
    resource("visits") {
        string("note")
        permissions { read(authenticated); write(authenticated) }
    }
}

/** A made-up library: lending a copy takes one and records a loan, in one action. */
private val librarySchema: Schema = schema {
    auditLog(readers = setOf("librarian"))
    resource("copies") {
        string("title") { required() }
        int("available") { required(); min(0) }
        action("lend") {
            input { string("borrower") { required() } }
            allow("librarian")
        }
        permissions { read(authenticated); write("librarian") }
    }
    resource("loans") {
        reference("copyId", "copies") { required() }
        string("borrower") { required() }
        permissions { read("librarian") }
    }
}

/** `X-User` names the caller and `X-Roles` lists roles. No header means anonymous. */
private val headerIdentities = IdentityResolver { call ->
    call.request.headers["X-User"]?.let { Identity(it, call.request.headers["X-Roles"].orEmpty().split(',').filter(String::isNotBlank).toSet()) }
}

private fun HttpRequestBuilder.caller(user: String, vararg roles: String) {
    header("X-User", user)
    header("X-Roles", roles.joinToString(","))
}

private fun HttpRequestBuilder.json(text: String) {
    contentType(ContentType.Application.Json)
    setBody(text)
}

private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

private val fixedClock = object : Clock {
    override fun now(): Instant = Instant.parse("2026-10-23T10:00:00Z")
}

class AuditTest {
    private fun app(schema: Schema, configure: BaseKtConfig.() -> Unit = {}, audit: BaseKtAuditConfig.() -> Unit = {}, test: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                install(BaseKt) {
                    dataSource = InMemoryDataSource()
                    this.schema = schema
                    identities = headerIdentities
                    clock = fixedClock
                    configure()
                }
                install(BaseKtAudit, audit)
            }
            test()
        }

    private suspend fun ApplicationTestBuilder.entries(query: String = ""): List<JsonObject> {
        val response = client.get("/api/audit?limit=100$query") { caller("boss", "manager", "librarian") }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.json()["items"]!!.jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.text(field: String) = this[field]!!.jsonPrimitive.content
    private fun JsonObject.changes() = Json.parseToJsonElement(text("changes")).jsonObject

    @Test
    fun `creates, updates and deletes are logged with who did them and what changed`() = app(nurserySchema, audit = { redact("plants.supplierCode") }) {
        client.post("/api/plants") { caller("ana", "gardener"); json("""{"id": "p1", "name": "Fern", "price": "4.50", "supplierCode": "S-77"}""") }
        client.patch("/api/plants/p1") { caller("ben", "gardener"); json("""{"price": "5", "supplierCode": "S-78"}""") }
        client.delete("/api/plants/p1") { caller("ana", "gardener") }

        val (created, updated, deleted) = entries("&sort=operationId").let { list -> listOf("create", "update", "delete").map { op -> list.single { it.text("operation") == op } } }
        assertEquals("ana", created.text("actor"))
        assertEquals("plants", created.text("resource"))
        assertEquals("p1", created.text("recordId"))
        assertEquals("2026-10-23T10:00:00Z", created.text("at"))
        assertEquals("""{"name":"Fern","price":"4.5","supplierCode":"[redacted]"}""", created.changes().toString())

        assertEquals("ben", updated.text("actor"))
        assertEquals(
            """{"price":{"from":"4.5","to":"5"},"supplierCode":{"from":"[redacted]","to":"[redacted]"}}""",
            updated.changes().toString(),
        )
        assertEquals("Fern", deleted.changes()["name"]!!.jsonPrimitive.content)
        assertTrue(listOf(created, updated, deleted).map { it.text("operationId") }.toSet().size == 3)
    }

    @Test
    fun `a write that rolls back leaves no entry, and skipped resources aren't logged`() = app(
        nurserySchema,
        configure = { hooks { beforeCreate("plants") { plant -> if ((plant["name"] as Value.Text).value == "Weed") veto("name", "No weeds.") else proceed(plant) } } },
        audit = { skip("visits") },
    ) {
        assertEquals(HttpStatusCode.UnprocessableEntity, client.post("/api/plants") { caller("ana", "gardener"); json("""{"name": "Weed"}""") }.status)
        client.post("/api/visits") { caller("ana"); json("""{"note": "hello"}""") }
        assertEquals(emptyList(), entries())
    }

    @Test
    fun `the log can be read and filtered, and not written`() = app(nurserySchema) {
        client.post("/api/plants") { caller("ana", "gardener"); json("""{"id": "p1", "name": "Fern"}""") }
        client.post("/api/plants") { caller("ana", "gardener"); json("""{"id": "p2", "name": "Moss"}""") }
        assertEquals(listOf("p2"), entries("&recordId=p2").map { it.text("recordId") })

        assertEquals(HttpStatusCode.Forbidden, client.get("/api/audit") { caller("ana", "gardener") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/audit") { caller("boss", "manager"); json("""{"operation": "create"}""") }.status)
        val id = entries().first().text("id")
        assertEquals(HttpStatusCode.Forbidden, client.patch("/api/audit/$id") { caller("boss", "manager"); json("""{"actor": "nobody"}""") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/audit/$id") { caller("boss", "manager") }.status)
    }

    @Test
    fun `an action is logged with its input, and its writes share its operation`() {
        val copies = librarySchema.resource("copies")!!
        val loans = librarySchema.resource("loans")!!
        app(librarySchema, configure = {
            actions {
                on("copies", "lend") { input ->
                    val copy = (record!![Resource.ID] as Value.Text).value
                    val available = (record!!["available"] as Value.Integer).value
                    if (available < 1) fail("None left.")
                    update(copies, copy, mapOf("available" to Value.Integer(available - 1)))
                    create(loans, mapOf("copyId" to Value.Text(copy), "borrower" to input["borrower"]))
                    null
                }
            }
        }) {
            client.post("/api/copies") { caller("lib", "librarian"); json("""{"id": "c1", "title": "Dune", "available": 1}""") }
            assertEquals(HttpStatusCode.OK, client.post("/api/copies/c1/actions/lend") { caller("lib", "librarian"); json("""{"borrower": "Ana"}""") }.status)
            assertEquals(HttpStatusCode.UnprocessableEntity, client.post("/api/copies/c1/actions/lend") { caller("lib", "librarian"); json("""{"borrower": "Ben"}""") }.status)

            val lent = entries("&action=lend")
            assertEquals(setOf("action", "update", "create"), lent.map { it.text("operation") }.toSet())
            assertEquals(1, lent.map { it.text("operationId") }.toSet().size)
            val action = lent.single { it.text("operation") == "action" }
            assertEquals("""{"borrower":"Ana"}""", action.text("input"))
            assertEquals("c1", action.text("recordId"))
            assertEquals("""{"available":{"from":1,"to":0}}""", lent.single { it.text("operation") == "update" }.text("changes"))
            // The copy's create, plus the three entries of the one action that went through; the failed one left none.
            assertEquals(4, entries().size)
        }
    }

    @Test
    fun `install checks the order, the log's shape and the names it's given`() {
        fun failure(schema: Schema, audit: BaseKtAuditConfig.() -> Unit = {}, withBaseKt: Boolean = true): String =
            assertFailsWith<Exception> {
                testApplication {
                    application {
                        if (withBaseKt) install(BaseKt) { dataSource = InMemoryDataSource(); this.schema = schema }
                        install(BaseKtAudit, audit)
                    }
                    client.get("/")
                }
            }.message.orEmpty()

        assertTrue("Install BaseKt before" in failure(nurserySchema, withBaseKt = false))
        assertTrue("no 'log' resource" in failure(nurserySchema, audit = { resource = "log" }))
        val writable = schema { resource("audit") { string("actor"); permissions { write("anyone") } } }
        assertTrue("can't be the audit log" in failure(writable).also { assertTrue("no 'at' field" in it, it) })
        assertTrue("plants.colour" in failure(nurserySchema, audit = { redact("plants.colour") }))
        assertTrue("trees" in failure(nurserySchema, audit = { skip("trees") }))
    }
}
