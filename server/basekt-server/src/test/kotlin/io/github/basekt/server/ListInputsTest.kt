package io.github.basekt.server

import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import io.github.basekt.schema.schema
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** A made-up harbour: a ship's manifest is sealed with the list of crate codes it carries, and a berth takes tags. */
private val harbourSchema: Schema = schema {
    resource("crates") {
        string("code") { required() }
        permissions { read(authenticated); write(authenticated) }
    }
    resource("berths") {
        string("name") { required() }
        action("moor") {
            input {
                string("tags") { list(); length(max = 8) }
                reference("crateIds", "crates") { required(); list() }
                int("loads") { list(); min(0) }
            }
            allow(authenticated)
        }
        permissions { read(authenticated); write(authenticated) }
    }
}

class ListInputsTest {
    private var seen: Map<String, Value?> = emptyMap()

    private fun harbour(test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = harbourSchema
                identities = headerIdentities
                actions {
                    on("berths", "moor") { input ->
                        seen = input
                        JsonObject(mapOf("crates" to JsonPrimitive((input["crateIds"] as Value.Items).values.size)))
                    }
                }
            }
        }
        client.post("/api/berths") { caller("u1"); json("""{"id": "b1", "name": "North"}""") }
        for (id in listOf("k1", "k2")) client.post("/api/crates") { caller("u1"); json("""{"id": "$id", "code": "$id"}""") }
        test()
    }

    private suspend fun ApplicationTestBuilder.moor(body: String) =
        client.post("/api/berths/b1/actions/moor") { caller("u1"); json(body) }

    @Test
    fun `an action gets its list input as a typed list`() = harbour {
        val response = moor("""{"tags": ["a", "b"], "crateIds": ["k1", "k2"], "loads": [3, 4]}""")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, response.json()["crates"]!!.jsonPrimitive.content.toInt())
        assertEquals(Value.Items(listOf(Value.Text("a"), Value.Text("b"))), seen["tags"])
        assertEquals(Value.Items(listOf(Value.Integer(3), Value.Integer(4))), seen["loads"])
    }

    @Test
    fun `each item is checked, and a required list needs an item`() = harbour {
        val long = moor("""{"tags": ["fine", "far too long"], "crateIds": ["k1"]}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, long.status)
        assertEquals("tags", long.json()["fields"]!!.jsonArray.single().jsonObject["field"]!!.jsonPrimitive.content)

        val negative = moor("""{"crateIds": ["k1"], "loads": [1, -1]}""")
        assertEquals("min", negative.json()["fields"]!!.jsonArray.single().jsonObject["code"]!!.jsonPrimitive.content)

        val empty = moor("""{"crateIds": []}""")
        assertEquals("required", empty.json()["fields"]!!.jsonArray.single().jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.UnprocessableEntity, moor("{}").status)
    }

    @Test
    fun `a list must be a JSON array of the field's type`() = harbour {
        for (body in listOf(
            """{"crateIds": "k1"}""",
            """{"crateIds": ["k1", 2]}""",
            """{"crateIds": ["k1"], "loads": ["x"]}""",
            """{"crateIds": ["k1"], "loads": 3}""",
        )) {
            val response = moor(body)
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status, body)
            assertEquals("type", response.json()["fields"]!!.jsonArray.single().jsonObject["code"]!!.jsonPrimitive.content, body)
        }
    }

    @Test
    fun `every reference in a list must exist`() = harbour {
        val response = moor("""{"crateIds": ["k1", "nope"]}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals("missing_reference", response.json()["fields"]!!.jsonArray.single().jsonObject["code"]!!.jsonPrimitive.content)
    }
}
