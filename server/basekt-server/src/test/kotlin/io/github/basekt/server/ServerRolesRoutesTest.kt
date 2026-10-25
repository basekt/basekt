package io.github.basekt.server

import io.github.basekt.data.memory.InMemoryDataSource
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** Server roles on a resolver with no auth module: the made-up catalogue and clinics. */
class ServerRolesRoutesTest {
    @Test
    fun `configured roles let a caller write the catalogue`() = testApplication {
        val config = MapApplicationConfig().apply { put("librarian", listOf("lib")) }
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = catalogueSchema
                identities = headerIdentities.withServerRoles(ServerRoles.from(config))
                validator("isbn", isbnValidator)
            }
        }
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/authors") { caller("other"); json("""{"name": "Le Guin"}""") }.status)
        assertEquals(HttpStatusCode.Created, client.post("/api/authors") { caller("lib"); json("""{"name": "Le Guin"}""") }.status)
    }

    @Test
    fun `a lookup grants clinic attributes on top of the caller's own`() = testApplication {
        val clinicsOf = mapOf("s1" to setOf("c2"))
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = clinicSchema
                identities = headerIdentities.withServerRoles(
                    ServerRoles { identity, _ -> Grants(attributes = mapOf("clinic" to clinicsOf[identity.id].orEmpty())) },
                )
            }
        }
        for (body in listOf("""{"id": "c1", "name": "North"}""", """{"id": "c2", "name": "South"}""", """{"id": "c3", "name": "East"}""")) {
            assertEquals(HttpStatusCode.Created, client.post("/api/clinics") { caller("d1", listOf("director")); json(body) }.status)
        }
        val response = client.get("/api/clinics") { caller("s1", listOf("staff"), mapOf("clinic" to listOf("c1"))) }
        assertEquals(listOf("c1", "c2"), response.json()["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.sorted())
    }
}
