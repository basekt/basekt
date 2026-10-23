package io.github.basekt.admin

import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Schema
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

/** A made-up set of depots: keepers manage the crates of their own depots, and wardens every depot's. */
private val depotSchema: Schema = schema {
    resource("crates") {
        string("depot") { required() }
        string("label") { required() }
        permissions { read("keeper", "warden"); write("keeper", "warden"); scope("depot", attribute = "depot", unless = setOf("warden")) }
    }
}

/** `X-User`, `X-Roles`, and `X-Depots` for the caller's `depot` attribute. */
private val depotIdentities = IdentityResolver { call ->
    call.request.headers["X-User"]?.let { user ->
        val depots = call.request.headers["X-Depots"].orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        Identity(user, setOfNotNull(call.request.headers["X-Roles"]), mapOf("depot" to depots))
    }
}

class AdminScopesTest {
    private val base = "/admin/api"

    private fun HttpRequestBuilder.keeper(vararg depots: String) {
        header("X-User", "k1")
        header("X-Roles", "keeper")
        header("X-Depots", depots.joinToString(","))
    }

    private fun HttpRequestBuilder.warden() {
        header("X-User", "w1")
        header("X-Roles", "warden")
    }

    private fun ApplicationTestBuilder.depots() {
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = depotSchema
                identities = depotIdentities
            }
            install(BaseKtAdmin)
        }
    }

    private suspend fun ApplicationTestBuilder.seed() {
        for ((depot, label) in listOf("east" to "Bolts", "east" to "Nuts", "west" to "Gears")) {
            val response = client.post("$base/resources/crates/records") {
                warden()
                contentType(ContentType.Application.Json)
                setBody("""{"depot": "$depot", "label": "$label"}""")
            }
            assertEquals(HttpStatusCode.Created, response.status)
        }
    }

    @Test
    fun `lists and their totals hold only the caller's records`() = testApplication {
        depots()
        seed()
        val own = client.get("$base/resources/crates/records") { keeper("east") }.json()
        assertEquals(2, own["items"]!!.jsonArray.size)
        assertEquals(2, own["total"]!!.jsonPrimitive.long)
        assertEquals(0, client.get("$base/resources/crates/records?search=gears") { keeper("east") }.json()["total"]!!.jsonPrimitive.long)
        assertEquals(3, client.get("$base/resources/crates/records") { warden() }.json()["total"]!!.jsonPrimitive.long)
    }

    @Test
    fun `the schema tells a panel which values the caller may give a scoped field`() = testApplication {
        depots()
        fun field(schema: kotlinx.serialization.json.JsonObject, name: String) = schema["resources"]!!.jsonArray.single().jsonObject["fields"]!!
            .jsonArray.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == name }

        val keeper = client.get("$base/schema") { keeper("west", "east") }.json()
        assertEquals(listOf("east", "west"), field(keeper, "depot")["scopedValues"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonNull, field(keeper, "label")["scopedValues"])
        assertEquals(JsonNull, field(client.get("$base/schema") { warden() }.json(), "depot")["scopedValues"])
    }
}
