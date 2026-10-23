package io.github.basekt.admin

import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Value
import io.github.basekt.schema.schema
import io.github.basekt.server.BaseKt
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** A made-up greenhouse: any gardener waters a bed, and only the head gardener may reset them all. */
private val greenhouseSchema = schema {
    resource("beds") {
        string("plant") { required() }
        bool("watered")
        action("water") {
            input { int("litres") { required(); min(1) } }
            allow("gardener", "head")
        }
        action("resetAll", target = io.github.basekt.schema.ActionTarget.RESOURCE) { allow("head") }
        permissions { read("gardener", "head") }
    }
}

class AdminActionsTest {
    private fun app(test: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        val beds = greenhouseSchema.resource("beds")!!
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = greenhouseSchema
                identities = headerIdentities
                actions {
                    on("beds", "water") { update(beds, (record!!["id"] as Value.Text).value, mapOf("watered" to Value.Bool(true))); null }
                    on("beds", "resetAll") { null }
                }
            }
            install(BaseKtAdmin)
        }
        test()
    }

    private fun JsonObject.actionNames() =
        this["resources"]!!.jsonArray.single().jsonObject["actions"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    @Test
    fun `the schema lists only the actions the caller may run, with their input`() = app {
        assertEquals(listOf("water"), client.get("/admin/api/schema") { asUser("gardener") }.json().actionNames())
        val head = client.get("/admin/api/schema") { asUser("head") }.json()
        assertEquals(listOf("water", "resetAll"), head.actionNames())
        val water = head["resources"]!!.jsonArray.single().jsonObject["actions"]!!.jsonArray.first().jsonObject
        assertEquals("record", water["target"]!!.jsonPrimitive.content)
        assertEquals("litres", water["input"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `actions run through the admin routes`() = app {
        assertEquals(HttpStatusCode.NoContent, client.post("/admin/api/resources/beds/actions/resetAll") { asUser("head") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/admin/api/resources/beds/actions/resetAll") { asUser("gardener") }.status)
        val missing = client.postJson("/admin/api/resources/beds/records/nope/actions/water", """{"litres": 2}""", "gardener")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        val invalid = client.postJson("/admin/api/resources/beds/records/nope/actions/water", """{"litres": 0}""", "gardener")
        assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
    }
}
