package io.github.basekt.server

import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Before and after hooks, on both made-up schemas, through REST and through the service with no transport at all. */
class HooksTest {
    private fun catalogue(hooks: HooksBuilder.() -> Unit, test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = catalogueSchema
                identities = headerIdentities
                validator("isbn", isbnValidator)
                hooks(hooks)
            }
        }
        test()
    }

    @Test
    fun `a before-create hook can modify the record`() = catalogue({
        beforeCreate("authors") { record -> proceed(record + ("name" to Value.Text((record["name"] as Value.Text).value.trim()))) }
    }) {
        val created = client.postJson("/api/authors", """{"name": "  Ursula  "}""", "librarian")
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("Ursula", created.json()["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a veto on a field is a validation error, and a plain veto is rejected`() = catalogue({
        beforeCreate("books") { record ->
            if ((record["loans"] as Value.Integer?)?.value == 13L) veto("loans", "Thirteen is unlucky.") else proceed(record)
        }
        beforeDelete("authors") { deletion -> veto("'${deletion.id}' still has books.") }
    }) {
        val unlucky = client.postJson("/api/books", """{"title": "T", "loans": 13}""", "librarian")
        assertEquals(HttpStatusCode.UnprocessableEntity, unlucky.status)
        val body = unlucky.json()
        assertEquals("validation_failed", body["error"]!!.jsonPrimitive.content)
        val field = body["fields"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("loans", "rejected", "Thirteen is unlucky."), listOf("field", "code", "message").map { field[it]!!.jsonPrimitive.content })

        client.postJson("/api/authors", """{"id": "a1", "name": "Ann"}""", "librarian")
        val refused = client.delete("/api/authors/a1") { asUser("reader") }
        assertEquals(HttpStatusCode.Forbidden, refused.status, "permissions are checked before hooks run")
        val vetoed = client.delete("/api/authors/a1") { asUser("librarian") }
        assertEquals(HttpStatusCode.UnprocessableEntity, vetoed.status)
        assertEquals("rejected", vetoed.json()["error"]!!.jsonPrimitive.content)
        assertEquals("'a1' still has books.", vetoed.json()["message"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, client.get("/api/authors/a1").status)
    }

    @Test
    fun `hook output is validated again`() = catalogue({
        beforeCreate("books") { record -> proceed(record + ("loans" to Value.Integer(-1))) }
    }) {
        val invalid = client.postJson("/api/books", """{"title": "T"}""", "librarian")
        assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
        assertEquals("loans", invalid.json()["fields"]!!.jsonArray.single().jsonObject["field"]!!.jsonPrimitive.content)
    }

    @Test
    fun `before-update hooks see the current record and after-hooks see the result`() = runTest {
        val seen = mutableListOf<String>()
        val service = trackerService {
            beforeUpdate("tasks") { update ->
                val wasDone = (update.current["done"] as Value.Bool?)?.value == true
                when {
                    wasDone && update.changes["done"] == Value.Bool(false) -> veto("A done task can't be reopened.")
                    update.changes["done"] == Value.Bool(true) -> proceed(update.changes + ("priority" to null))
                    else -> proceed(update.changes)
                }
            }
            afterUpdate("tasks") { updated -> seen += "${updated.before["priority"]} -> ${updated.after["priority"]} by ${caller?.id}" }
        }
        val lead = Identity("lee", setOf("lead"))
        service.create(service.resource("projects"), lead, obj("id" to "p1", "title" to "Launch"))
        service.create(service.resource("tasks"), lead, obj("id" to "t1", "summary" to "Ship", "projectId" to "p1", "priority" to 3))

        val done = service.update(service.resource("tasks"), lead, "t1", obj("done" to true))
        assertNull(done["priority"])
        assertEquals(listOf("3 -> null by lee"), seen)

        val error = assertFailsWith<ApiException> { service.update(service.resource("tasks"), lead, "t1", obj("done" to false)) }
        assertEquals(ErrorCode.REJECTED, error.error.error)
        assertEquals("A done task can't be reopened.", error.error.message)
    }

    @Test
    fun `hooks write in the same transaction, and a failing after-hook rolls the write back`() = runTest {
        val service = trackerService {
            afterCreate("tasks") { created ->
                // Bump the project's title to count its tasks, through the write's own transaction.
                val projects = schemaResource("projects")
                val projectId = (created["projectId"] as Value.Text).value
                val project = data.get(projects, projectId)!!
                data.update(projects, projectId, mapOf("title" to Value.Text("${(project["title"] as Value.Text).value}+")))
                if (created["summary"] == Value.Text("explode")) error("after-hook failed")
            }
            afterDelete("tasks") { deleted -> data.delete(schemaResource("projects"), (deleted["projectId"] as Value.Text).value) }
        }
        val lead = Identity("lee", setOf("lead"))
        val projects = service.resource("projects")
        val tasks = service.resource("tasks")
        service.create(projects, lead, obj("id" to "p1", "title" to "Launch"))
        service.create(tasks, lead, obj("id" to "t1", "summary" to "Ship", "projectId" to "p1"))
        assertEquals("Launch+", title(service.get(projects, lead, "p1")))

        assertFailsWith<IllegalStateException> { service.create(tasks, lead, obj("id" to "t2", "summary" to "explode", "projectId" to "p1")) }
        assertEquals("Launch+", title(service.get(projects, lead, "p1")))
        assertFailsWith<ApiException> { service.get(tasks, lead, "t2") }

        service.delete(tasks, lead, "t1")
        assertFailsWith<ApiException> { service.get(projects, lead, "p1") }
    }

    @Test
    fun `a hook that sets an unknown field or the wrong type is a programming error`() = runTest {
        val service = trackerService {
            beforeCreate("projects") { record -> proceed(record + ("colour" to Value.Text("red"))) }
            beforeCreate("tasks") { record -> proceed(record + ("priority" to Value.Text("high"))) }
        }
        val lead = Identity("lee", setOf("lead"))
        assertFailsWith<IllegalStateException> { service.create(service.resource("projects"), lead, obj("title" to "P")) }
        assertFailsWith<IllegalStateException> { service.create(service.resource("tasks"), lead, obj("summary" to "T", "projectId" to "p")) }
    }

    @Test
    fun `hooks must name resources of the schema`() {
        val error = assertFailsWith<IllegalArgumentException> { trackerService { afterDelete("invoices") { } } }
        assertEquals("Hooks name resources the schema doesn't have: invoices.", error.message)
    }

    @Test
    fun `REST runs the same hooks as the service`() = testApplication {
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = trackerSchema
                identities = headerIdentities
                hooks { beforeDelete("projects") { veto("Projects are archived, not deleted.") } }
            }
        }
        client.postJson("/api/projects", """{"id": "p1", "title": "Launch"}""", "lead")
        val vetoed = client.delete("/api/projects/p1") { asUser("lead") }
        assertEquals(HttpStatusCode.UnprocessableEntity, vetoed.status)
        assertEquals("rejected", vetoed.json()["error"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, client.get("/api/projects/p1") { asUser() }.status)
    }

    private fun trackerService(hooks: HooksBuilder.() -> Unit) =
        ResourceService(trackerSchema, InMemoryDataSource(), hooks = HooksBuilder().apply(hooks).build())

    private fun schemaResource(name: String): Resource = trackerSchema.resource(name)!!

    private fun title(record: Record) = (record["title"] as Value.Text).value

    private fun obj(vararg pairs: Pair<String, Any?>) = JsonObject(
        pairs.associate { (key, value) ->
            key to when (value) {
                null -> kotlinx.serialization.json.JsonNull
                is Boolean -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
        },
    )
}
