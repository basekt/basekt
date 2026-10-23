package io.github.basekt.admin

import io.github.basekt.schema.schema
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** A made-up allotment: plots are free, rented, or fallow, and wardens rent them out. */
private val allotmentSchema = schema {
    resource("plots") {
        string("label") { required() }
        enum("state", listOf("free", "rented", "fallow"))
        workflow("state", initial = "free") {
            transition("rent", from = "free", to = "rented") { allow("warden") }
            transition("rest", from = listOf("free", "rented"), to = "fallow") { allow("warden") }
        }
        permissions { read("warden"); write("warden") }
    }
}

class AdminWorkflowTest {
    @Test
    fun `transitions are listed with their states and run through the admin routes`() = testApplication {
        admin(allotmentSchema)
        val actions = client.get("/admin/api/schema") { asUser("warden") }.json()["resources"]!!.jsonArray.single()
            .jsonObject["actions"]!!.jsonArray.map { it.jsonObject }.associateBy { it["name"]!!.jsonPrimitive.content }
        assertEquals(setOf("rent", "rest"), actions.keys)
        assertEquals("state", actions.getValue("rest")["stateField"]!!.jsonPrimitive.content)
        assertEquals(listOf("free", "rented"), actions.getValue("rest")["from"]!!.jsonArray.map { it.jsonPrimitive.content })

        val created = client.postJson("/admin/api/resources/plots/records", """{"id": "p1", "label": "North 3"}""", "warden")
        assertEquals("free", created.json()["state"]!!.jsonPrimitive.content)
        val rented = client.postJson("/admin/api/resources/plots/records/p1/actions/rent", "{}", "warden")
        assertEquals(HttpStatusCode.OK, rented.status)
        assertEquals("rented", rented.json()["state"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Conflict, client.postJson("/admin/api/resources/plots/records/p1/actions/rent", "{}", "warden").status)
    }
}
