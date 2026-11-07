package io.github.basekt.server

import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.IdFormat
import io.github.basekt.schema.InvalidSchemaException
import io.github.basekt.schema.Schema
import io.github.basekt.schema.schema
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Ids of a declared shape, in a made-up library whose books came from MongoDB and whose loans are UUIDs. */
class IdFormatsTest {
    private val library: Schema = schema {
        resource("books") {
            ids(IdFormat.OBJECT_ID)
            string("title") { required() }
            permissions { read(everyone); write(authenticated) }
        }
        resource("loans") {
            ids(IdFormat.UUID)
            belongsTo("book", "books") { required() }
            permissions { read(everyone); write(authenticated) }
        }
    }

    private val member: HttpRequestBuilder.() -> Unit = { caller("m1") }

    @Test
    fun `the server makes ids of the declared shape, whatever the data source would, and refuses others`() = testApplication {
        application {
            install(BaseKt) {
                // The in-memory source makes UUIDs: the declared ObjectIds win.
                dataSource = InMemoryDataSource()
                schema = library
                identities = headerIdentities
            }
        }
        val book = client.post("/api/books") { member(); json("""{"title": "Dune"}""") }
        assertEquals(HttpStatusCode.Created, book.status)
        val bookId = book.json()["id"]!!.jsonPrimitive.content
        assertTrue(IdFormat.OBJECT_ID.matches(bookId), bookId)

        val loan = client.post("/api/loans") { member(); json("""{"bookId": "$bookId"}""") }
        assertTrue(IdFormat.UUID.matches(loan.json()["id"]!!.jsonPrimitive.content))

        // An id a create gives must have the shape too, so a typo never becomes a record no reference can reach.
        val given = client.post("/api/books") { member(); json("""{"id": "67b47e9f26f61a5a182eccc1", "title": "Emma"}""") }
        assertEquals(HttpStatusCode.Created, given.status)
        val wrong = client.post("/api/books") { member(); json("""{"id": "dune-2", "title": "Dune 2"}""") }
        assertEquals(HttpStatusCode.UnprocessableEntity, wrong.status)
        val error = wrong.json()["fields"]!!.jsonArray.single().jsonObject
        assertEquals("type", error["code"]!!.jsonPrimitive.content)
        assertEquals("A 'books' id is a MongoDB ObjectId: 24 hex digits.", error["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an ObjectId starts with the seconds it was made, and a UUID is version 4`() {
        val made = IdFormat.OBJECT_ID.newId(Instant.fromEpochSeconds(0x67b47e9f))
        assertTrue(made.startsWith("67b47e9f") && made.length == 24, made)
        val uuid = IdFormat.UUID.newId()
        assertEquals('4', uuid[14])
        assertTrue(uuid[19] in "89ab", uuid)
        assertTrue(IdFormat.UUID.matches(uuid) && !IdFormat.OBJECT_ID.matches(uuid))
    }

    @Test
    fun `a key id takes no format, since its fields make it`() {
        val failure = assertFailsWith<InvalidSchemaException> {
            schema {
                resource("trees") { string("name") }
                resource("harvests") {
                    ids(IdFormat.UUID)
                    belongsTo("tree", "trees") { required() }
                    int("season") { required() }
                    keyedBy("treeId", "season")
                }
            }
        }
        assertEquals(listOf("A key id is made from its fields, so it has no format of its own."), failure.problems.map { it.message })
    }
}
