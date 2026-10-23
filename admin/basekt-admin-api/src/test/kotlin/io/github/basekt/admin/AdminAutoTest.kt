package io.github.basekt.admin

import io.github.basekt.schema.schema
import io.ktor.client.request.get
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** A made-up visitor book: numbered visits, stamped by the server. */
private val visitorSchema = schema {
    resource("visits") {
        string("ref") { sequence("V-", digits = 3) }
        string("guest") { required() }
        timestamps()
        permissions { read(authenticated); write(authenticated) }
    }
}

class AdminAutoTest {
    @Test
    fun `the schema tells a panel which fields the server sets`() = testApplication {
        admin(visitorSchema)
        val fields = client.get("/admin/api/schema") { asUser() }.json()["resources"]!!.jsonArray.single().jsonObject["fields"]!!
            .jsonArray.map { it.jsonObject }.associateBy { it["name"]!!.jsonPrimitive.content }
        fun auto(name: String) = fields.getValue(name)["auto"]

        assertEquals("sequence", (auto("ref") as JsonObject)["kind"]!!.jsonPrimitive.content)
        assertEquals("V-", (auto("ref") as JsonObject)["prefix"]!!.jsonPrimitive.content)
        assertEquals("createdAt", (auto("createdAt") as JsonObject)["kind"]!!.jsonPrimitive.content)
        assertEquals("updatedAt", (auto("updatedAt") as JsonObject)["kind"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, auto("guest"))

        val created = client.postJson("/admin/api/resources/visits/records", """{"guest": "Ana"}""")
        assertEquals("V-001", created.json()["ref"]!!.jsonPrimitive.content)
    }
}
