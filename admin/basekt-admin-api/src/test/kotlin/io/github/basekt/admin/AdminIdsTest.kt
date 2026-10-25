package io.github.basekt.admin

import io.github.basekt.schema.schema
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** A made-up choir: singers have names for ids, and each sings one part per piece. */
private val choirSchema = schema {
    resource("singers") {
        naturalId { pattern("[a-z]+") }
        string("voice")
        permissions { read(authenticated); write(authenticated) }
    }
    resource("pieces") {
        string("title") { required() }
        permissions { read(authenticated); write(authenticated) }
    }
    resource("parts") {
        belongsTo("singer", "singers") { required() }
        belongsTo("piece", "pieces") { required() }
        keyedBy("singerId", "pieceId")
        permissions { read(authenticated); write(authenticated) }
    }
}

class AdminIdsTest {
    @Test
    fun `the schema tells a panel where ids come from, and creates follow it`() = testApplication {
        admin(choirSchema)
        val resources = client.get("/admin/api/schema") { asUser() }.json()["resources"]!!.jsonArray.map { it.jsonObject }
            .associateBy { it["name"]!!.jsonPrimitive.content }
        fun id(name: String) = resources.getValue(name)["id"] as JsonObject

        assertEquals("natural", id("singers")["source"]!!.jsonPrimitive.content)
        assertEquals("[a-z]+", id("singers")["rules"]!!.jsonArray.single().jsonObject["regex"]!!.jsonPrimitive.content)
        assertEquals("generated", id("pieces")["source"]!!.jsonPrimitive.content)
        assertEquals(listOf("singerId", "pieceId"), id("parts")["key"]!!.jsonArray.map { it.jsonPrimitive.content })

        assertEquals(HttpStatusCode.Created, client.postJson("/admin/api/resources/singers/records", """{"id": "ana", "voice": "alto"}""").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, client.postJson("/admin/api/resources/singers/records", """{"voice": "bass"}""").status)
        val piece = client.postJson("/admin/api/resources/pieces/records", """{"title": "Ave"}""").json()["id"]!!.jsonPrimitive.content
        val part = client.postJson("/admin/api/resources/parts/records", """{"singerId": "ana", "pieceId": "$piece"}""").json()
        assertEquals("ana~$piece", part["id"]!!.jsonPrimitive.content)
    }
}
