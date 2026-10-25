package io.github.basekt.server

import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.IdSource
import io.github.basekt.schema.IdSpec
import io.github.basekt.schema.InvalidSchemaException
import io.github.basekt.schema.OnDelete
import io.github.basekt.schema.Schema
import io.github.basekt.schema.schema
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Ids that callers choose and ids made of two fields, in a made-up package registry and a made-up orchard. */
class NaturalIdsAndKeysTest {
    /** Handles are usernames, packages have reverse-DNS names, and a handle stars a package at most once. */
    private val registrySchema: Schema = schema {
        resource("handles") {
            naturalId { length(min = 3, max = 20); pattern("[a-z0-9_]+", "Use lowercase letters, digits and '_'.") }
            string("display")
            permissions { read(everyone); write(authenticated) }
        }
        resource("packages") {
            naturalId { pattern("[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+") }
            string("summary")
            permissions { read(everyone); write(authenticated) }
        }
        resource("stars") {
            belongsTo("handle", "handles", onDelete = OnDelete.CASCADE) { required() }
            belongsTo("pkg", "packages", field = "packageId", onDelete = OnDelete.CASCADE) { required() }
            string("note")
            keyedBy("handleId", "packageId")
            permissions { read(everyone); write(authenticated) }
        }
    }

    /** Trees have generated ids; a tree's harvest is recorded once a season. */
    private val orchardSchema: Schema = schema {
        resource("trees") {
            string("variety") { required() }
            permissions { read(authenticated); write("grower") }
        }
        resource("harvests") {
            belongsTo("tree", "trees", onDelete = OnDelete.CASCADE) { required() }
            int("season") { required(); min(2000) }
            decimal("kilos")
            keyedBy("treeId", "season")
            permissions { read(authenticated); write("grower") }
        }
    }

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

    private val member: HttpRequestBuilder.() -> Unit = { caller("m1") }
    private val grower: HttpRequestBuilder.() -> Unit = { caller("g1", listOf("grower")) }

    private suspend fun HttpResponse.fieldError(): Pair<String, String> {
        val error = json()["fields"]!!.jsonArray.single().jsonObject
        return error["field"]!!.jsonPrimitive.content to error["code"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.create(path: String, body: String, who: HttpRequestBuilder.() -> Unit = member): HttpResponse =
        client.post(path) { who(); json(body) }

    @Test
    fun `a natural id is required, checked against its rules, unique and never renamed`() = app(registrySchema) {
        val missing = create("/api/handles", """{"display": "Alice"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, missing.status)
        assertEquals("id" to "required", missing.fieldError())
        assertEquals("id" to "pattern", create("/api/handles", """{"id": "Alice!"}""").fieldError())
        assertEquals("id" to "length", create("/api/handles", """{"id": "al"}""").fieldError())
        assertEquals("id" to "type", create("/api/packages", """{"id": "com.example/tools"}""").fieldError())

        assertEquals(HttpStatusCode.Created, create("/api/handles", """{"id": "alice", "display": "Alice"}""").status)
        assertEquals(HttpStatusCode.Conflict, create("/api/handles", """{"id": "alice"}""").status)
        assertEquals("Alice", client.get("/api/handles/alice").json()["display"]!!.jsonPrimitive.content)

        val renamed = client.patch("/api/handles/alice") { member(); json("""{"id": "alicia"}""") }
        assertEquals("id" to "read_only", renamed.fieldError())
        assertEquals(HttpStatusCode.NotFound, client.get("/api/handles/alicia").status)

        assertEquals(HttpStatusCode.Created, create("/api/packages", """{"id": "com.example.tools"}""").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/packages/com.example.tools").status)
    }

    @Test
    fun `a key id is made of its fields, addresses the record by both and can't repeat`() = app(registrySchema) {
        create("/api/handles", """{"id": "alice"}""")
        create("/api/handles", """{"id": "bob"}""")
        create("/api/packages", """{"id": "com.example.tools"}""")

        val star = create("/api/stars", """{"handleId": "alice", "packageId": "com.example.tools", "note": "neat"}""")
        assertEquals(HttpStatusCode.Created, star.status)
        assertEquals("alice~com.example.tools", star.json()["id"]!!.jsonPrimitive.content)
        assertEquals("neat", client.get("/api/stars/alice~com.example.tools").json()["note"]!!.jsonPrimitive.content)

        val again = create("/api/stars", """{"handleId": "alice", "packageId": "com.example.tools"}""")
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("There is already a 'stars' with this handleId and packageId.", again.json()["message"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Created, create("/api/stars", """{"handleId": "bob", "packageId": "com.example.tools"}""").status)

        assertEquals("id" to "read_only", create("/api/stars", """{"id": "x~y", "handleId": "bob", "packageId": "com.example.tools"}""").fieldError())
        assertEquals("packageId" to "required", create("/api/stars", """{"handleId": "bob"}""").fieldError())
        val moved = client.patch("/api/stars/alice~com.example.tools") { member(); json("""{"handleId": "bob"}""") }
        assertEquals("handleId" to "read_only", moved.fieldError())
        val kept = client.patch("/api/stars/alice~com.example.tools") { member(); json("""{"handleId": "alice", "note": "still neat"}""") }
        assertEquals(HttpStatusCode.OK, kept.status)

        // Filtering by one part finds every record with it.
        val bobs = client.get("/api/stars?packageId=com.example.tools&sort=id").json()["items"]!!.jsonArray
        assertEquals(listOf("alice~com.example.tools", "bob~com.example.tools"), bobs.map { it.jsonObject["id"]!!.jsonPrimitive.content })
    }

    @Test
    fun `of many callers creating the same key at once, one wins and the rest get a conflict`() = app(registrySchema) {
        create("/api/handles", """{"id": "alice"}""")
        create("/api/packages", """{"id": "com.example.tools"}""")
        val statuses = coroutineScope {
            (1..20).map { n ->
                async { create("/api/stars", """{"handleId": "alice", "packageId": "com.example.tools", "note": "$n"}""").status }
            }.awaitAll()
        }
        assertEquals(1, statuses.count { it == HttpStatusCode.Created })
        assertEquals(19, statuses.count { it == HttpStatusCode.Conflict })
        assertEquals(1, client.get("/api/stars").json()["items"]!!.jsonArray.size)
    }

    @Test
    fun `key parts are escaped so that any values make one id, and numbers are parts too`() = app(orchardSchema) {
        // Every id is checked, a generated resource's given ones too: no '/' in a path segment.
        assertEquals("id" to "type", create("/api/trees", """{"id": "row~4/b", "variety": "Fuji"}""", grower).fieldError())

        val tree = create("/api/trees", """{"id": "row~4", "variety": "Fuji"}""", grower)
        assertEquals(HttpStatusCode.Created, tree.status)
        val created = create("/api/harvests", """{"treeId": "row~4", "season": 2026, "kilos": "41.5"}""", grower).json()
        val id = created["id"]!!.jsonPrimitive.content
        assertEquals("row%7E4~2026", id)
        assertEquals(listOf("row~4", "2026"), IdSpec.partsOf(id))
        // In a path, the id's own '%' is sent as %25.
        assertEquals("41.5", client.get("/api/harvests/row%257E4~2026") { grower() }.json()["kilos"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Conflict, create("/api/harvests", """{"treeId": "row~4", "season": 2026}""", grower).status)
        assertEquals(HttpStatusCode.Created, create("/api/harvests", """{"treeId": "row~4", "season": 2025}""", grower).status)

        // Deleting the tree takes its harvests with it.
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/trees/row~4") { grower() }.status)
        assertEquals(0, client.get("/api/harvests") { grower() }.json()["items"]!!.jsonArray.size)
    }

    @Test
    fun `id specs are data that round-trips through JSON and is checked`() {
        assertEquals(registrySchema, Schema.fromJson(registrySchema.toJson()))
        assertEquals(IdSource.KEY, orchardSchema.resource("harvests")!!.id.source)
        assertTrue(""""source":"natural"""" in registrySchema.toJson())

        val problems = assertFailsWith<InvalidSchemaException> {
            schema {
                resource("tags") {
                    string("label")
                    bool("pinned")
                    keyedBy("label", "pinned")
                }
                resource("notes") {
                    string("body")
                    keyedBy("body")
                }
                resource("links") {
                    reference("tagId", "tags") { required() }
                    belongsTo("tag", "tags", field = "otherTagId", onDelete = OnDelete.SET_NULL) { required() }
                }
            }
        }.problems.map { it.path to it.message }
        assertEquals(
            listOf(
                "resources.tags.id.key" to "'pinned' is a bool field; a key takes string, enum, reference, int and long fields.",
                "resources.notes.id.key" to "A key needs at least two fields; for one, use a natural id.",
                "resources.links.relations.tag" to "'otherTagId' is required, so deleting the target can't set it to null.",
            ),
            problems,
        )
    }
}
