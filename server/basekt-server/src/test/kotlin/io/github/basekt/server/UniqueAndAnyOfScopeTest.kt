package io.github.basekt.server

import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.schema
import io.ktor.client.request.HttpRequestBuilder
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

/** Unique fields in a made-up gym, and scopes with several ways in for made-up freight between docks. */
class UniqueAndAnyOfScopeTest {
    private val gymSchema: Schema = schema {
        resource("members") {
            string("email") { required(); unique() }
            string("badge") { unique() }
            string("name")
            permissions { read(authenticated); write("desk") }
        }
    }

    /** Freight moves from one dock to another; a dock's crew handles it from either end. */
    private val freightSchema: Schema = schema {
        resource("loads") {
            string("fromDock") { required() }
            string("toDock") { required() }
            string("cargo")
            permissions {
                read(authenticated)
                write(authenticated)
                scopeAnyOf(unless = setOf("dispatcher")) {
                    attribute("fromDock", "dock")
                    attribute("toDock", "dock")
                }
            }
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

    private val desk: HttpRequestBuilder.() -> Unit = { caller("d1", listOf("desk")) }
    private val dispatcher: HttpRequestBuilder.() -> Unit = { caller("p1", listOf("dispatcher")) }
    private fun crew(vararg docks: String): HttpRequestBuilder.() -> Unit =
        { caller("c1", listOf("crew"), mapOf("dock" to docks.toList())) }

    private suspend fun ApplicationTestBuilder.ids(who: HttpRequestBuilder.() -> Unit): List<String> {
        val response = client.get("/api/loads") { who() }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.json()["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
    }

    @Test
    fun `a unique field refuses a second record with the same value`() = app(gymSchema) {
        assertEquals(HttpStatusCode.Created, client.post("/api/members") { desk(); json("""{"id": "m1", "email": "a@x.io", "badge": "B1"}""") }.status)
        val clash = client.post("/api/members") { desk(); json("""{"id": "m2", "email": "a@x.io"}""") }
        assertEquals(HttpStatusCode.UnprocessableEntity, clash.status)
        val error = clash.json()["fields"]!!.jsonArray.single().jsonObject
        assertEquals("email", error["field"]!!.jsonPrimitive.content)
        assertEquals("unique", error["code"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/members/m2") { desk() }.status)
    }

    @Test
    fun `an update may keep its own value but not take another's`() = app(gymSchema) {
        client.post("/api/members") { desk(); json("""{"id": "m1", "email": "a@x.io", "badge": "B1"}""") }
        client.post("/api/members") { desk(); json("""{"id": "m2", "email": "b@x.io", "badge": "B2"}""") }
        assertEquals(HttpStatusCode.OK, client.patch("/api/members/m1") { desk(); json("""{"email": "a@x.io", "name": "Ada"}""") }.status)
        val clash = client.patch("/api/members/m2") { desk(); json("""{"badge": "B1"}""") }
        assertEquals(HttpStatusCode.UnprocessableEntity, clash.status)
        assertEquals("unique", clash.json()["fields"]!!.jsonArray.single().jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, client.patch("/api/members/m1") { desk(); json("""{"badge": "B9"}""") }.status)
        assertEquals(HttpStatusCode.OK, client.patch("/api/members/m2") { desk(); json("""{"badge": "B1"}""") }.status)
    }

    @Test
    fun `empty values never clash`() = app(gymSchema) {
        assertEquals(HttpStatusCode.Created, client.post("/api/members") { desk(); json("""{"email": "a@x.io"}""") }.status)
        assertEquals(HttpStatusCode.Created, client.post("/api/members") { desk(); json("""{"email": "b@x.io"}""") }.status)
        assertEquals(HttpStatusCode.Created, client.post("/api/members") { desk(); json("""{"email": "c@x.io", "badge": null}""") }.status)
    }

    @Test
    fun `a scope with several ways in lists records that match any of them`() = app(freightSchema) {
        for (body in listOf(
            """{"id": "l1", "fromDock": "north", "toDock": "south", "cargo": "tyres"}""",
            """{"id": "l2", "fromDock": "east", "toDock": "north", "cargo": "oil"}""",
            """{"id": "l3", "fromDock": "east", "toDock": "west", "cargo": "grain"}""",
        )) assertEquals(HttpStatusCode.Created, client.post("/api/loads") { dispatcher(); json(body) }.status)

        assertEquals(listOf("l1", "l2"), ids(crew("north")))
        assertEquals(listOf("l1"), ids(crew("south")))
        assertEquals(listOf("l1", "l2", "l3"), ids(crew("south", "east")))
        assertEquals(emptyList(), ids(crew()))
        assertEquals(listOf("l1", "l2", "l3"), ids(dispatcher))
        assertEquals(HttpStatusCode.OK, client.get("/api/loads/l2") { crew("north")() }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/loads/l3") { crew("north")() }.status)
    }

    @Test
    fun `a write must put the record into one of the caller's ways in`() = app(freightSchema) {
        val created = client.post("/api/loads") { crew("north")(); json("""{"id": "l1", "fromDock": "north", "toDock": "west"}""") }
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals(HttpStatusCode.Created, client.post("/api/loads") { crew("north")(); json("""{"id": "l2", "fromDock": "east", "toDock": "north"}""") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/loads") { crew("north")(); json("""{"id": "l3", "fromDock": "east", "toDock": "west"}""") }.status)

        assertEquals(HttpStatusCode.OK, client.patch("/api/loads/l1") { crew("north")(); json("""{"cargo": "tyres"}""") }.status)
        assertEquals(HttpStatusCode.OK, client.patch("/api/loads/l1") { crew("north")(); json("""{"toDock": "south"}""") }.status)
        // Both ends moved away from the caller's dock.
        assertEquals(HttpStatusCode.Forbidden, client.patch("/api/loads/l1") { crew("north")(); json("""{"fromDock": "east", "toDock": "west"}""") }.status)
        assertEquals(HttpStatusCode.NotFound, client.patch("/api/loads/l1") { crew("west")(); json("""{"cargo": "x"}""") }.status)
    }
}
