package io.github.basekt.server

import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.InvalidSchemaException
import io.github.basekt.schema.Schema
import io.github.basekt.schema.schema
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
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
import kotlin.test.assertFailsWith

/** A made-up market: stalls belong to a hall, and each sale takes its stall's hall so it can be scoped by it. */
private val marketSchema: Schema = schema {
    resource("stalls") {
        string("hall") { required() }
        string("name") { required() }
        permissions { read(authenticated); write("manager"); scope("hall", attribute = "hall", unless = setOf("director")) }
    }
    resource("sales") {
        reference("stallId", "stalls") { required() }
        string("hall") { inherit("stallId", "hall") }
        int("cents") { required() }
        permissions { read(authenticated); write(authenticated); scope("hall", attribute = "hall", unless = setOf("director")) }
    }
}

class InheritedFieldsTest {
    private fun market(test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = marketSchema
                identities = headerIdentities
            }
        }
        for ((id, hall) in listOf("s1" to "north", "s2" to "south")) {
            client.post("/api/stalls") { director(); json("""{"id": "$id", "hall": "$hall", "name": "Stall $id"}""") }
        }
        test()
    }

    private fun HttpRequestBuilder.director() = caller("d1", listOf("director", "manager"))
    private fun hall(name: String): HttpRequestBuilder.() -> Unit = { caller("u1", listOf("clerk"), mapOf("hall" to listOf(name))) }

    @Test
    fun `a record takes its parent's value, and scopes use it`() = market {
        val sale = client.post("/api/sales") { hall("north")(); json("""{"id": "x1", "stallId": "s1", "cents": 500}""") }
        assertEquals(HttpStatusCode.Created, sale.status)
        assertEquals("north", sale.json()["hall"]!!.jsonPrimitive.content)
        client.post("/api/sales") { director(); json("""{"id": "x2", "stallId": "s2", "cents": 700}""") }

        val north = client.get("/api/sales") { hall("north")() }.json()["items"]!!.jsonArray
        assertEquals(listOf("x1"), north.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(2, client.get("/api/sales") { director() }.json()["items"]!!.jsonArray.size)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/sales/x2") { hall("north")() }.status)
    }

    @Test
    fun `a create under a parent outside the caller's scope is refused and leaves nothing behind`() = market {
        val foreign = client.post("/api/sales") { hall("north")(); json("""{"id": "x1", "stallId": "s2", "cents": 500}""") }
        assertEquals(HttpStatusCode.Forbidden, foreign.status)
        assertEquals(0, client.get("/api/sales") { director() }.json()["items"]!!.jsonArray.size)
    }

    @Test
    fun `a client can't set an inherited field, and the schema checks what it copies`() = market {
        val set = client.post("/api/sales") { hall("north")(); json("""{"stallId": "s1", "cents": 1, "hall": "south"}""") }
        assertEquals(HttpStatusCode.UnprocessableEntity, set.status)
        assertFailsWith<InvalidSchemaException> {
            schema { resource("a") { string("hall") { inherit("missingId", "hall") } } }
        }
        assertFailsWith<InvalidSchemaException> {
            schema {
                resource("b") { int("hall") }
                resource("a") { reference("bId", "b"); string("hall") { inherit("bId", "hall") } }
            }
        }
    }
}
