package io.github.basekt.server

import io.github.basekt.data.Filter
import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Operation
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** Record scopes: the made-up clinics scope records by a caller attribute, and the journal by the caller's id. */
class ScopedRoutesTest {
    private fun app(schema: Schema, test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                this.schema = schema
                identities = headerIdentities
            }
        }
        test()
    }

    private val director: HttpRequestBuilder.() -> Unit = { caller("d1", listOf("director")) }
    private fun staff(vararg clinics: String): HttpRequestBuilder.() -> Unit =
        { caller("s1", listOf("staff"), mapOf("clinic" to clinics.toList())) }

    private suspend fun ApplicationTestBuilder.seedClinics() {
        for (body in listOf("""{"id": "c1", "name": "North"}""", """{"id": "c2", "name": "South"}""")) {
            assertEquals(HttpStatusCode.Created, client.post("/api/clinics") { director(); json(body) }.status)
        }
        for (body in listOf("""{"id": "a1", "clinicId": "c1", "patient": "Ada"}""", """{"id": "a2", "clinicId": "c2", "patient": "Bo"}""")) {
            assertEquals(HttpStatusCode.Created, client.post("/api/appointments") { director(); json(body) }.status)
        }
    }

    private suspend fun ApplicationTestBuilder.ids(path: String, who: HttpRequestBuilder.() -> Unit): List<String> {
        val response = client.get(path) { who() }
        assertEquals(HttpStatusCode.OK, response.status, path)
        return response.json()["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
    }

    @Test
    fun `staff list and read only their own clinics' records`() = app(clinicSchema) {
        seedClinics()
        assertEquals(listOf("a1"), ids("/api/appointments", staff("c1")))
        assertEquals(listOf("c1"), ids("/api/clinics", staff("c1")))
        assertEquals(listOf("a1"), ids("/api/appointments?patient[contains]=a", staff("c1")))
        assertEquals(HttpStatusCode.OK, client.get("/api/appointments/a1") { staff("c1")() }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/appointments/a2") { staff("c1")() }.status)
        assertEquals(listOf("a1", "a2"), ids("/api/appointments", staff("c1", "c2")))
    }

    @Test
    fun `a role in unless reaches every record`() = app(clinicSchema) {
        seedClinics()
        assertEquals(listOf("a1", "a2"), ids("/api/appointments", director))
        assertEquals(HttpStatusCode.OK, client.patch("/api/appointments/a2") { director(); json("""{"clinicId": "c1"}""") }.status)
    }

    @Test
    fun `a caller without the attribute reaches nothing`() = app(clinicSchema) {
        seedClinics()
        assertEquals(emptyList(), ids("/api/appointments", staff()))
        val response = client.post("/api/appointments") { staff()(); json("""{"patient": "Cy"}""") }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `a create gets the caller's only value, or must choose one of theirs`() = app(clinicSchema) {
        seedClinics()
        val filled = client.post("/api/appointments") { staff("c1")(); json("""{"patient": "Cy"}""") }
        assertEquals(HttpStatusCode.Created, filled.status)
        assertEquals("c1", filled.json()["clinicId"]!!.jsonPrimitive.content)

        val foreign = client.post("/api/appointments") { staff("c1")(); json("""{"clinicId": "c2", "patient": "Cy"}""") }
        assertEquals(HttpStatusCode.Forbidden, foreign.status)

        val ambiguous = client.post("/api/appointments") { staff("c1", "c2")(); json("""{"patient": "Cy"}""") }
        assertEquals(HttpStatusCode.UnprocessableEntity, ambiguous.status)
        val error = ambiguous.json()["fields"]!!.jsonArray.single().jsonObject
        assertEquals("clinicId", error["field"]!!.jsonPrimitive.content)
        assertEquals("required", error["code"]!!.jsonPrimitive.content)

        val chosen = client.post("/api/appointments") { staff("c1", "c2")(); json("""{"clinicId": "c2", "patient": "Cy"}""") }
        assertEquals(HttpStatusCode.Created, chosen.status)
    }

    @Test
    fun `updates and deletes stay inside the caller's scope`() = app(clinicSchema) {
        seedClinics()
        assertEquals(HttpStatusCode.OK, client.patch("/api/appointments/a1") { staff("c1")(); json("""{"patient": "Ada L."}""") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.patch("/api/appointments/a1") { staff("c1")(); json("""{"clinicId": "c2"}""") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.patch("/api/appointments/a1") { staff("c1")(); json("""{"clinicId": null}""") }.status)
        assertEquals(HttpStatusCode.NotFound, client.patch("/api/appointments/a2") { staff("c1")(); json("""{"patient": "X"}""") }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/appointments/a2") { staff("c1")() }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/appointments/a1") { staff("c1")() }.status)
        assertEquals(listOf("a2"), ids("/api/appointments", director))
    }

    @Test
    fun `relation routes are scoped on both ends`() = app(clinicSchema) {
        seedClinics()
        assertEquals(listOf("a1"), ids("/api/clinics/c1/appointments", staff("c1")))
        assertEquals(HttpStatusCode.NotFound, client.get("/api/clinics/c2/appointments") { staff("c1")() }.status)
        assertEquals("c1", client.get("/api/appointments/a1/clinic") { staff("c1")() }.json()["id"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/appointments/a2/clinic") { staff("c1")() }.status)
    }

    @Test
    fun `everyone reads the journal, and only an entry's author changes it`() = app(journalSchema) {
        val writer: HttpRequestBuilder.() -> Unit = { caller("u1") }
        val reader: HttpRequestBuilder.() -> Unit = { caller("u2") }
        val created = client.post("/api/entries") { writer(); json("""{"id": "e1", "body": "Hello"}""") }
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("u1", created.json()["authorId"]!!.jsonPrimitive.content)

        assertEquals(listOf("e1"), ids("/api/entries", reader))
        assertEquals(HttpStatusCode.OK, client.get("/api/entries/e1") { reader() }.status)
        assertEquals(HttpStatusCode.Forbidden, client.patch("/api/entries/e1") { reader(); json("""{"body": "Mine now"}""") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/entries/e1") { reader() }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/entries") { reader(); json("""{"authorId": "u1", "body": "Forged"}""") }.status)
        assertEquals(HttpStatusCode.OK, client.patch("/api/entries/e1") { writer(); json("""{"body": "Hello again"}""") }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/entries/e1") { writer() }.status)
    }

    @Test
    fun `the service exposes the scope as a filter and as values`() {
        val service = ResourceService(clinicSchema, InMemoryDataSource())
        val appointments = service.resource("appointments")
        val staff = Identity("s1", setOf("staff"), mapOf("clinic" to setOf("c1")))
        assertEquals(Filter.In("clinicId", listOf(Value.Text("c1"))), service.scopeFilter(appointments, Operation.READ, staff))
        assertEquals(null, service.scopeFilter(appointments, Operation.READ, Identity("d1", setOf("director"))))
        assertEquals(Filter.Or(emptyList()), service.scopeFilter(appointments, Operation.READ, Identity("s2", setOf("staff"))))
        assertEquals(mapOf("clinicId" to setOf("c1")), service.scopedValues(appointments, Operation.CREATE, staff))
    }
}
