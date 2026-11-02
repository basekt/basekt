package io.github.basekt.admin

import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import io.github.basekt.schema.schema
import io.github.basekt.server.BaseKt
import io.github.basekt.server.Identity
import io.github.basekt.server.IdentityResolver
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A made-up lending library: books point at their writer and their shelf, and only librarians see shelves. */
private val librarySchema: Schema = schema {
    resource("writers") {
        string("firstName")
        string("lastName") { required() }
        string("note") { hidden() }
        adminView { label("{lastName}, {firstName}") }
        permissions { read(authenticated); write("librarian") }
    }
    resource("shelves") {
        string("code") { required() }
        string("room")
        adminView { label("code") }
        permissions { read("librarian"); write("librarian") }
    }
    resource("books") {
        string("title") { required() }
        belongsTo("writer", "writers")
        belongsTo("shelf", "shelves")
        permissions { read(authenticated); write("librarian") }
    }
}

/** A made-up set of depots: keepers see the crates of their own depot only; wardens see every crate. */
private val depotSchema: Schema = schema {
    resource("crates") {
        string("depot") { required() }
        string("label") { required() }
        adminView { label("label") }
        permissions { read("keeper", "warden"); write("warden"); scope("depot", attribute = "depot", unless = setOf("warden")) }
    }
    resource("moves") {
        string("note") { required() }
        reference("crateId", "crates")
        permissions { read("keeper", "warden"); write("warden") }
    }
}

class AdminLabelsTest {
    private val base = "/admin/api"

    private suspend fun ApplicationTestBuilder.seedLibrary() {
        client.postJson("$base/resources/writers/records", """{"id": "w1", "firstName": "Ada", "lastName": "Lovelace"}""", "librarian")
        client.postJson("$base/resources/writers/records", """{"id": "w2", "lastName": "Byron"}""", "librarian")
        client.postJson("$base/resources/shelves/records", """{"id": "s1", "code": "A-1", "room": "East"}""", "librarian")
        client.postJson("$base/resources/books/records", """{"id": "b1", "title": "Notes", "writerId": "w1", "shelfId": "s1"}""", "librarian")
        client.postJson("$base/resources/books/records", """{"id": "b2", "title": "  Poems  ", "writerId": "w2"}""", "librarian")
    }

    private suspend fun ApplicationTestBuilder.labels(path: String, vararg roles: String): JsonObject =
        client.get("$base/$path") { asUser(*roles) }.json()["labels"]!!.jsonObject

    private fun JsonObject.of(resource: String): Map<String, String>? =
        this[resource]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }

    @Test
    fun `a page names its own records and the records its references point to`() = testApplication {
        admin(librarySchema)
        seedLibrary()

        val labels = labels("resources/books/records", "librarian")
        assertEquals(mapOf("b1" to "Notes", "b2" to "Poems"), labels.of("books"), "the first text field, trimmed")
        assertEquals(mapOf("w1" to "Lovelace, Ada", "w2" to "Byron"), labels.of("writers"), "a template; a dangling comma is trimmed")
        assertEquals(mapOf("s1" to "A-1"), labels.of("shelves"), "a field name")
    }

    @Test
    fun `a caller gets no labels for records they may not read`() = testApplication {
        admin(librarySchema)
        seedLibrary()

        val labels = labels("resources/books/records", "reader")
        assertEquals(mapOf("w1" to "Lovelace, Ada", "w2" to "Byron"), labels.of("writers"))
        assertNull(labels.of("shelves"), "a reader may not read shelves, so their labels are left out")
        assertEquals(emptyMap(), client.get("$base/resources/shelves/labels?id=s1") { asUser("reader") }.json()["labels"]!!.jsonObject)
    }

    @Test
    fun `the labels route names the records it is asked about`() = testApplication {
        admin(librarySchema) {
            defaultPageSize = 2
            maxPageSize = 2
        }
        seedLibrary()

        val answer = client.get("$base/resources/writers/labels?id=w1&id=nobody") { asUser("reader") }
        assertEquals(HttpStatusCode.OK, answer.status)
        assertEquals(mapOf("w1" to "Lovelace, Ada"), answer.json()["labels"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content })

        val tooMany = client.get("$base/resources/writers/labels?id=a&id=b&id=c") { asUser("reader") }
        assertEquals(HttpStatusCode.BadRequest, tooMany.status)
    }

    @Test
    fun `a label function wins over the schema, and long labels are cut`() = testApplication {
        admin(librarySchema) {
            label("writers") { record -> (record["lastName"] as? Value.Text)?.value?.uppercase() }
            label("books") { record -> if ((record["title"] as? Value.Text)?.value == "Notes") null else "x".repeat(200) }
        }
        seedLibrary()

        val labels = labels("resources/books/records", "librarian")
        assertEquals(mapOf("w1" to "LOVELACE", "w2" to "BYRON"), labels.of("writers"))
        assertEquals("Notes", labels.of("books")!!["b1"], "null falls back to the schema's label")
        val long = labels.of("books")!!.getValue("b2")
        assertEquals(MAX_LABEL_LENGTH, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun `labels follow the caller's scopes`() = testApplication {
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = depotSchema
                identities = IdentityResolver { call ->
                    call.request.headers["X-User"]?.let { user ->
                        val depots = call.request.headers["X-Depots"].orEmpty().split(',').filter { it.isNotBlank() }.toSet()
                        Identity(user, setOfNotNull(call.request.headers["X-Roles"]), mapOf("depot" to depots))
                    }
                }
            }
            install(BaseKtAdmin)
        }
        fun HttpRequestBuilder.warden() {
            header("X-User", "w")
            header("X-Roles", "warden")
        }
        suspend fun create(path: String, body: String) = client.post("$base/$path") {
            warden()
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        create("resources/crates/records", """{"id": "c1", "depot": "north", "label": "Bolts"}""")
        create("resources/crates/records", """{"id": "c2", "depot": "south", "label": "Nails"}""")
        create("resources/moves/records", """{"id": "m1", "note": "in", "crateId": "c1"}""")
        create("resources/moves/records", """{"id": "m2", "note": "out", "crateId": "c2"}""")

        val keeper = client.get("$base/resources/moves/records") {
            header("X-User", "k")
            header("X-Roles", "keeper")
            header("X-Depots", "north")
        }.json()["labels"]!!.jsonObject
        assertEquals(mapOf("c1" to "Bolts"), keeper.of("crates"), "the south crate is outside the keeper's depot")

        val warden = client.get("$base/resources/moves/records") { warden() }.json()["labels"]!!.jsonObject
        assertEquals(mapOf("c1" to "Bolts", "c2" to "Nails"), warden.of("crates"))
    }

    @Test
    fun `templates are filled in from a record`() {
        val record = mapOf("first" to Value.Text("Ada"), "last" to Value.Text("Lovelace"), "n" to Value.Integer(3))
        assertEquals("Lovelace, Ada", renderLabel("{last}, {first}", record))
        assertEquals("Ada", renderLabel("{missing} - {first}", record))
        assertEquals("No. 3", renderLabel("No. {n}", record))
        assertEquals("Ada", renderLabel("first", record))
        assertNull(renderLabel("{missing}", record))
        assertEquals(listOf("last", "first"), labelFields("{last}, {first}"))
    }

    @Test
    fun `invalid labels are reported, and label functions must name a resource`() {
        fun problems(template: String) = schema {
            resource("writers") {
                string("lastName")
                string("note") { hidden() }
                adminView { label(template) }
            }
        }.adminViewProblems()
        assertEquals(emptyList(), problems("{lastName} ({id})"))
        assertTrue(problems("{nickname}").single().contains("isn't a field"))
        assertTrue(problems("note").single().contains("a hidden field"))
        assertTrue(problems("Writer: {}").single().contains("no field"))

        val error = assertFailsWith<Throwable> {
            testApplication {
                admin(librarySchema) { label("authors") { "x" } }
                startApplication()
            }
        }
        assertTrue(generateSequence(error) { it.cause }.any { "label functions for resources the schema doesn't have" in it.message.orEmpty() })
    }
}
