package io.github.basekt.admin

import io.github.basekt.data.Filter
import io.github.basekt.data.Page
import io.github.basekt.data.Query
import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import io.github.basekt.schema.schema
import io.github.basekt.server.BaseKt
import io.github.basekt.server.FilterOperator
import io.github.basekt.server.RecordProvider
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A made-up help desk: tickets are stored, and who is waiting on the phone line right now comes from the switchboard. */
private val helpDeskSchema: Schema = schema {
    resource("tickets") {
        string("subject") { required() }
        permissions { read("agent"); write("agent") }
    }
    resource("callers") {
        provided()
        string("name") { required() }
        enum("line", listOf("sales", "support"))
        int("waitedSeconds")
        action("pickUp") { allow("agent") }
        permissions { read("agent") }
    }
}

/** The switchboard: a few callers in a list, filtered by line and searched by name. */
private class Switchboard : RecordProvider {
    private val callers = listOf(caller("c1", "Ana", "sales", 40), caller("c2", "Ben", "support", 300), caller("c3", "Bea", "support", 15))

    override val filters = mapOf("line" to setOf(FilterOperator.EQ), "name" to setOf(FilterOperator.CONTAINS))
    override val sorts = setOf("waitedSeconds")

    override suspend fun list(query: Query): Page = Page(callers.filter { query.filter?.let { f -> matches(f, it) } ?: true }.take(query.limit), null)

    override suspend fun get(id: String): Record? = callers.firstOrNull { it[Resource.ID] == Value.Text(id) }

    private fun matches(filter: Filter, caller: Record): Boolean = when (filter) {
        is Filter.And -> filter.filters.all { matches(it, caller) }
        is Filter.Or -> filter.filters.any { matches(it, caller) }
        is Filter.Compare -> caller[filter.field] == filter.value
        is Filter.Contains -> (caller[filter.field] as Value.Text).value.contains(filter.text, ignoreCase = true)
        else -> error("Not declared: $filter")
    }

    private fun caller(id: String, name: String, line: String, waited: Long): Record =
        mapOf(Resource.ID to Value.Text(id), "name" to Value.Text(name), "line" to Value.Text(line), "waitedSeconds" to Value.Integer(waited))
}

/** A made-up kiln room: each kiln's temperature is read live, and the reader takes no filters and no sorts. */
private val kilnSchema: Schema = schema {
    resource("kilns") {
        provided()
        string("label")
        decimal("celsius")
        permissions { read(everyone) }
    }
}

private object Thermometers : RecordProvider {
    private val kiln: Record = mapOf(Resource.ID to Value.Text("k1"), "label" to Value.Text("Big kiln"), "celsius" to Value.Decimal.parseOrNull("1180.5"))

    override suspend fun list(query: Query): Page = Page(listOf(kiln), null)

    override suspend fun get(id: String): Record? = kiln.takeIf { id == "k1" }
}

class AdminProvidedTest {
    private fun app(schema: Schema, name: String, provider: RecordProvider, test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                this.schema = schema
                identities = headerIdentities
                providers { provide(name, provider) }
                if (name == "callers") actions { on("callers", "pickUp") { null } }
            }
            install(BaseKtAdmin)
        }
        test()
    }

    private suspend fun ApplicationTestBuilder.resource(name: String, vararg roles: String): JsonObject =
        client.get("/admin/api/schema") { asUser(*roles) }.json()["resources"]!!.jsonArray.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == name }

    @Test
    fun `the schema marks a provided resource read-only, with the filters, sorts and search its provider offers`() =
        app(helpDeskSchema, "callers", Switchboard()) {
            val callers = resource("callers", "agent")
            assertTrue(callers["readOnly"]!!.jsonPrimitive.boolean)
            assertEquals(listOf("read"), callers["operations"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf("waitedSeconds"), callers["sortable"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf("name"), callers["searchable"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(
                """[{"field":"name","operators":["contains"]},{"field":"line","operators":["eq"]}]""",
                callers["filters"].toString(),
            )
            assertEquals(listOf("pickUp"), callers["actions"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })

            val tickets = resource("tickets", "agent")
            assertEquals(false, tickets["readOnly"]!!.jsonPrimitive.boolean)
            assertEquals(JsonNull, tickets["filters"])
        }

    @Test
    fun `the panel's list, search and export read the provider, with no total, and its writes are refused`() =
        app(helpDeskSchema, "callers", Switchboard()) {
            val page = client.get("/admin/api/resources/callers/records?search=be&line=support") { asUser("agent") }
            assertEquals(HttpStatusCode.OK, page.status, page.bodyAsText())
            val body = page.json()
            assertEquals(listOf("c2", "c3"), body["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
            assertEquals(JsonNull, body["total"])

            val csv = client.get("/admin/api/resources/callers/export.csv") { asUser("agent") }
            assertEquals(HttpStatusCode.OK, csv.status)
            assertEquals(4, csv.bodyAsText().trim().lines().size)

            assertEquals(HttpStatusCode.BadRequest, client.get("/admin/api/resources/callers/records?waitedSeconds[gt]=10") { asUser("agent") }.status)

            val create = client.postJson("/admin/api/resources/callers/records", """{"name": "Cy"}""", "agent")
            assertEquals(HttpStatusCode.MethodNotAllowed, create.status)
            assertEquals("read_only", create.json()["error"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.MethodNotAllowed, client.delete("/admin/api/resources/callers/records/c1") { asUser("agent") }.status)
            val bulk = client.postJson("/admin/api/resources/callers/bulk-delete", """{"ids": ["c1", "c2"]}""", "agent")
            assertEquals(HttpStatusCode.MethodNotAllowed, bulk.status)

            val picked = client.postJson("/admin/api/resources/callers/records/c1/actions/pickUp", "", "agent")
            assertEquals(HttpStatusCode.OK, picked.status, picked.bodyAsText())
            assertEquals("Ana", picked.json()["name"]!!.jsonPrimitive.content)
        }

    @Test
    fun `a provider with no filters or sorts offers none to the panel`() = app(kilnSchema, "kilns", Thermometers) {
        val kilns = resource("kilns")
        assertEquals(JsonArray(emptyList()), kilns["filters"])
        assertEquals(JsonArray(emptyList()), kilns["sortable"])
        assertEquals(JsonArray(emptyList()), kilns["searchable"])
        val search = client.get("/admin/api/resources/kilns/records?search=big")
        assertEquals(HttpStatusCode.BadRequest, search.status)
        val record = client.get("/admin/api/resources/kilns/records/k1").json()
        assertEquals("1180.5", record["celsius"]!!.jsonPrimitive.content)
    }
}
