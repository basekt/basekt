package io.github.basekt.server

import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Schema
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** CRUD, validation, references and relations, on a made-up library catalogue. */
class CatalogueRoutesTest {
    private fun catalogue(test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = catalogueSchema
                identities = headerIdentities
                validator("isbn", isbnValidator)
            }
        }
        test()
    }

    @Test
    fun `GET schema returns the schema as JSON`() = catalogue {
        val response = client.get("/api/schema")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(catalogueSchema, Schema.fromJson(response.bodyAsText()))
    }

    @Test
    fun `create, get, update and delete a record`() = catalogue {
        val created = client.postJson("/api/authors", """{"id": "le-guin", "name": "Ursula K. Le Guin"}""", "librarian")
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("/api/authors/le-guin", created.headers[HttpHeaders.Location])

        val book = client.postJson(
            "/api/books",
            """{"title": "The Dispossessed", "isbn": "978-0-06-051275-6", "format": "paper", "authorId": "le-guin"}""",
            "librarian",
        )
        assertEquals(HttpStatusCode.Created, book.status)
        val id = book.json()["id"]!!.jsonPrimitive.content
        assertEquals(
            """{"id":"$id","title":"The Dispossessed","isbn":"978-0-06-051275-6","format":"paper","loans":null,"authorId":"le-guin"}""",
            client.get("/api/books/$id").bodyAsText(),
        )

        val patched = client.patchJson("/api/books/$id", """{"loans": 3}""", "librarian")
        assertEquals(HttpStatusCode.OK, patched.status)
        assertEquals(3, patched.json()["loans"]!!.jsonPrimitive.int)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/books/$id") { asUser("admin") }.status)
        val gone = client.get("/api/books/$id")
        assertEquals(HttpStatusCode.NotFound, gone.status)
        assertEquals("not_found", gone.json()["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `invalid fields come back as typed errors`() = catalogue {
        val response = client.postJson("/api/books", """{"isbn": "12", "format": "scroll", "loans": -1, "pages": 9}""", "librarian")
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        val body = response.json()
        assertEquals("validation_failed", body["error"]!!.jsonPrimitive.content)
        val codes = body["fields"]!!.jsonArray.associate {
            it.jsonObject["field"]!!.jsonPrimitive.content to it.jsonObject["code"]!!.jsonPrimitive.content
        }
        assertEquals(
            mapOf("title" to "required", "isbn" to "custom", "format" to "enum", "loans" to "min", "pages" to "unknown_field"),
            codes,
        )
    }

    @Test
    fun `a reference to a missing record is refused`() = catalogue {
        val response = client.postJson("/api/books", """{"title": "Orphan", "authorId": "nobody"}""", "librarian")
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertTrue("missing_reference" in response.bodyAsText())
        assertEquals("""{"items":[],"nextCursor":null}""", client.get("/api/books").bodyAsText())
    }

    @Test
    fun `a taken id is a conflict and a bad body is a bad request`() = catalogue {
        client.postJson("/api/authors", """{"id": "a1", "name": "A"}""", "librarian")
        assertEquals(HttpStatusCode.Conflict, client.postJson("/api/authors", """{"id": "a1", "name": "B"}""", "librarian").status)
        val bad = client.postJson("/api/authors", """["not", "an", "object"]""", "librarian")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals("bad_request", bad.json()["error"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.NotFound, client.patchJson("/api/authors/zz", """{"name": "Z"}""", "librarian").status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/authors/zz") { asUser("librarian") }.status)
    }

    @Test
    fun `permissions follow the caller's roles`() = catalogue {
        assertEquals(HttpStatusCode.OK, client.get("/api/books").status)
        val anonymous = client.post("/api/books") { json("""{"title": "x"}""") }
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
        assertEquals("unauthorized", anonymous.json()["error"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/api/books", """{"title": "x"}""", "reader").status)
        val created = client.postJson("/api/books", """{"id": "b1", "title": "x"}""", "librarian")
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/books/b1") { asUser("librarian") }.status)
    }

    @Test
    fun `belongsTo and hasMany relations have their own routes`() = catalogue {
        client.postJson("/api/authors", """{"id": "a1", "name": "Octavia E. Butler"}""", "librarian")
        client.postJson("/api/authors", """{"id": "a2", "name": "Someone Else"}""", "librarian")
        for ((id, title, author) in listOf(Triple("b1", "Kindred", "a1"), Triple("b2", "Dawn", "a1"), Triple("b3", "Other", "a2"))) {
            client.postJson("/api/books", """{"id": "$id", "title": "$title", "authorId": "$author"}""", "librarian")
        }
        client.postJson("/api/books", """{"id": "b4", "title": "Anonymous"}""", "librarian")

        assertEquals("Octavia E. Butler", client.get("/api/books/b2/author").json()["name"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/books/b4/author").status)

        val books = client.get("/api/authors/a1/books?sort=-title").json()["items"]!!.jsonArray
        assertEquals(listOf("Kindred", "Dawn"), books.map { it.jsonObject["title"]!!.jsonPrimitive.content })
        assertEquals(HttpStatusCode.NotFound, client.get("/api/authors/zz/books").status)
    }

    @Test
    fun `unknown resources are not found`() = catalogue {
        assertEquals(HttpStatusCode.NotFound, client.get("/api/shelves").status)
    }
}
