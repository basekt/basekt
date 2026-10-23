package io.github.basekt.server

import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import io.github.basekt.schema.schema
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A made-up club: members have a PIN that only the club's own code sees, and a hidden internal score. */
private val clubSchema: Schema = schema {
    resource("members") {
        string("name") { required() }
        string("pin") { hidden() }
        int("score") { hidden() }
        permissions { read(authenticated); write(authenticated) }
    }
}

class HiddenFieldsTest {
    private val data = InMemoryDataSource()
    private val members get() = clubSchema.resource("members")!!

    private fun club(test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(BaseKt) {
                dataSource = data
                schema = clubSchema
                identities = headerIdentities
                hooks {
                    beforeCreate("members") { member -> proceed(member + ("pin" to Value.Text("4321")) + ("score" to Value.Integer(7))) }
                }
            }
        }
        test()
    }

    @Test
    fun `trusted code writes a hidden field, and no response shows it`() = club {
        val created = client.post("/api/members") { caller("u1"); json("""{"id": "m1", "name": "Ana"}""") }
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals(setOf("id", "name"), created.json().keys)
        assertEquals(setOf("id", "name"), client.get("/api/members/m1") { caller("u1") }.json().keys)
        val listed = client.get("/api/members") { caller("u1") }.json()["items"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("id", "name"), listed.keys)
        assertEquals(Value.Text("4321"), runBlocking { data.get(members, "m1") }!!["pin"])
    }

    @Test
    fun `a client can't write, filter, sort or search by a hidden field`() = club {
        client.post("/api/members") { caller("u1"); json("""{"id": "m1", "name": "Ana"}""") }
        val write = client.post("/api/members") { caller("u1"); json("""{"name": "Bo", "pin": "0000"}""") }
        assertEquals(HttpStatusCode.UnprocessableEntity, write.status)
        val error = write.json()["fields"]!!.jsonArray.single().jsonObject
        assertEquals("pin", error["field"]!!.jsonPrimitive.content)
        assertEquals("read_only", error["code"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.UnprocessableEntity, client.patch("/api/members/m1") { caller("u1"); json("""{"score": 99}""") }.status)

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/members?pin=4321") { caller("u1") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/members?score[gt]=1") { caller("u1") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/members?sort=pin") { caller("u1") }.status)
        assertEquals(Value.Text("4321"), runBlocking { data.get(members, "m1") }!!["pin"])
    }

    @Test
    fun `the schema is data, so a hidden field survives JSON and the checker keeps it unrequired`() {
        assertEquals(clubSchema, Schema.fromJson(clubSchema.toJson()))
        assertTrue(clubSchema.resource("members")!!.field("pin")!!.hidden)
        assertFalse(clubSchema.resource("members")!!.field("name")!!.hidden)
        val problem = runCatching { schema { resource("a") { string("pin") { hidden(); required() } } } }.exceptionOrNull()
        assertTrue(problem is io.github.basekt.schema.InvalidSchemaException)
    }
}
