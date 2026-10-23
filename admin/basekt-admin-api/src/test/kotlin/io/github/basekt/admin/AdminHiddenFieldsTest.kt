package io.github.basekt.admin

import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.schema
import io.github.basekt.server.BaseKt
import io.github.basekt.server.Identity
import io.github.basekt.server.IdentityResolver
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** A made-up kennel: dogs have a hidden vet note that no panel form, list, sort or search may reach. */
class AdminHiddenFieldsTest {
    private val kennel = schema {
        resource("dogs") {
            string("name") { required() }
            string("vetNote") { hidden() }
            permissions { read("staff"); write("staff") }
        }
    }

    @Test
    fun `the admin API leaves a hidden field out of schema, records and search`() = testApplication {
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = kennel
                identities = IdentityResolver { Identity("s1", setOf("staff")) }
                hooks {
                    beforeCreate("dogs") { dog -> proceed(dog + ("vetNote" to io.github.basekt.schema.Value.Text("limps"))) }
                }
            }
            install(BaseKtAdmin)
        }
        val created = client.post("/admin/api/resources/dogs/records") {
            header("X-User", "s1")
            contentType(ContentType.Application.Json)
            setBody("""{"name": "Rex"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)

        val schema = client.get("/admin/api/schema").json()["resources"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("name"), schema["fields"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals(listOf("name"), schema["searchable"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("id", "name"), schema["sortable"]!!.jsonArray.map { it.jsonPrimitive.content })

        val record = client.get("/admin/api/resources/dogs/records").json()["items"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("id", "name"), record.keys)
        assertEquals(0, client.get("/admin/api/resources/dogs/records?search=limps").json()["total"]!!.jsonPrimitive.content.toInt())
    }
}
