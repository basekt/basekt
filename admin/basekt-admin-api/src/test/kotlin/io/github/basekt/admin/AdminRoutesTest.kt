package io.github.basekt.admin

import io.github.basekt.auth.AuthIdentities
import io.github.basekt.auth.JwtSettings
import io.github.basekt.auth.baseKtJwt
import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.server.BaseKt
import io.github.basekt.server.Identity
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class AdminRoutesTest {
    private val base = "/admin/api"

    private suspend fun ApplicationTestBuilder.schemaFor(vararg roles: String?): AdminSchema {
        val response = client.get("$base/schema") { if (roles.isNotEmpty() && roles[0] != null) asUser(*roles.filterNotNull().toTypedArray()) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        return AdminJson.decodeFromString(AdminSchema.serializer(), response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.seedStore() {
        assertEquals(HttpStatusCode.Created, client.postJson("$base/resources/bins/records", """{"id": "b1", "label": "A-1", "zone": "north"}""", "keeper").status)
        val parts = listOf(
            """{"id": "p1", "name": "Bolt", "quantity": 120, "cost": "0.10", "binId": "b1"}""",
            """{"id": "p2", "name": "Nut", "note": "metric, \"M6\"", "quantity": 80, "cost": "0.05", "binId": "b1"}""",
            """{"id": "p3", "name": "Washer", "quantity": 0, "cost": "0.02"}""",
            """{"id": "p4", "name": "=SUM(A1)", "note": "Bolt cutter", "quantity": 2, "cost": "19.90", "countedAt": "2026-10-01T08:00:00Z"}""",
        )
        for (part in parts) assertEquals(HttpStatusCode.Created, client.postJson("$base/resources/parts/records", part, "keeper").status)
    }

    private suspend fun ApplicationTestBuilder.ids(path: String, vararg roles: String): List<String> {
        val response = client.get(path) { asUser(*roles) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.json()["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
    }

    @Test
    fun `the schema shows each caller only what their roles allow`() = testApplication {
        admin(storeSchema)
        val anonymous = schemaFor(null)
        assertEquals(1, anonymous.version)
        assertEquals(null, anonymous.caller)
        assertEquals(emptyList(), anonymous.resources)

        val keeper = schemaFor("keeper")
        assertEquals(AdminCaller("u1", listOf("keeper")), keeper.caller)
        assertEquals(listOf("bins", "parts"), keeper.resources.map { it.name })
        val parts = keeper.resources.single { it.name == "parts" }
        assertEquals(listOf(io.github.basekt.schema.Operation.READ, io.github.basekt.schema.Operation.CREATE, io.github.basekt.schema.Operation.UPDATE), parts.operations)
        assertEquals(listOf("name", "note"), parts.searchable)
        assertEquals(listOf("id", "name", "note", "quantity", "cost", "countedAt", "binId"), parts.sortable)
        assertEquals(AdminRelation("bin", RelationKind.BELONGS_TO, "bins", "binId"), parts.relations.single())
        assertEquals(io.github.basekt.schema.FieldType.REFERENCE, parts.fields.single { it.name == "binId" }.type)

        val auditor = schemaFor("auditor")
        // Auditors may read the log only, plus what any signed-in caller reads.
        assertEquals(listOf("bins", "parts", "log"), auditor.resources.map { it.name })
        assertEquals(listOf(io.github.basekt.schema.Operation.READ), auditor.resources.single { it.name == "log" }.operations)
    }

    @Test
    fun `lists filter, sort, search and page with a total`() = testApplication {
        admin(storeSchema)
        seedStore()
        assertEquals(listOf("p1", "p4"), ids("$base/resources/parts/records?search=bolt", "keeper"))
        assertEquals(listOf("p1"), ids("$base/resources/parts/records?search=bolt&binId=b1", "keeper"))
        assertEquals(listOf("p1", "p2", "p4", "p3"), ids("$base/resources/parts/records?sort=-quantity", "keeper"))

        // "=SUM(A1)" sorts before "Bolt" and "Nut".
        val first = client.get("$base/resources/parts/records?sort=name&limit=2&quantity[gt]=0") { asUser("keeper") }.json()
        assertEquals(listOf("p4", "p1"), first["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(3, first["total"]!!.jsonPrimitive.long)
        assertEquals("true", first["hasNext"]!!.jsonPrimitive.content)
        val second = client.get("$base/resources/parts/records?sort=name&limit=2&quantity[gt]=0") {
            asUser("keeper")
            parameter("cursor", first["nextCursor"]!!.jsonPrimitive.content)
        }.json()
        assertEquals(listOf("p2"), second["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(JsonNull, second["nextCursor"])
    }

    @Test
    fun `detail, create, update and delete go through the service's rules`() = testApplication {
        admin(storeSchema)
        seedStore()
        val detail = client.get("$base/resources/parts/records/p2") { asUser("keeper") }.json()
        assertEquals("metric, \"M6\"", detail["note"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, detail["countedAt"])

        val invalid = client.postJson("$base/resources/parts/records", """{"name": "Pin", "quantity": -1, "binId": "nope"}""", "keeper")
        assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
        assertEquals(setOf("quantity"), invalid.json()["fields"]!!.jsonArray.map { it.jsonObject["field"]!!.jsonPrimitive.content }.toSet())
        val dangling = client.postJson("$base/resources/parts/records", """{"name": "Pin", "binId": "nope"}""", "keeper")
        assertEquals(HttpStatusCode.UnprocessableEntity, dangling.status)
        assertEquals("missing_reference", dangling.json()["fields"]!!.jsonArray.single().jsonObject["code"]!!.jsonPrimitive.content)

        val patched = client.patch("$base/resources/parts/records/p3") {
            asUser("keeper")
            contentType(ContentType.Application.Json)
            setBody("""{"quantity": 7}""")
        }
        assertEquals(HttpStatusCode.OK, patched.status)
        assertEquals("7", patched.json()["quantity"]!!.jsonPrimitive.content)

        assertEquals(HttpStatusCode.Forbidden, client.delete("$base/resources/parts/records/p3") { asUser("keeper") }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("$base/resources/parts/records/p3") { asUser("manager") }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("$base/resources/parts/records/p3") { asUser("keeper") }.status)
    }

    @Test
    fun `errors are typed JSON and nothing is HTML`() = testApplication {
        admin(storeSchema)
        suspend fun error(path: String, vararg roles: String): Pair<HttpStatusCode, String> {
            val response = client.get(path) { asUser(*roles) }
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters(), path)
            return response.status to response.json()["error"]!!.jsonPrimitive.content
        }
        assertEquals(HttpStatusCode.NotFound to "not_found", error("$base/resources/invoices/records", "keeper"))
        assertEquals(HttpStatusCode.BadRequest to "invalid_query", error("$base/resources/parts/records?colour=red", "keeper"))
        assertEquals(HttpStatusCode.BadRequest to "invalid_query", error("$base/resources/log/records?search=x&sort=nope", "auditor"))
        assertEquals(HttpStatusCode.Forbidden to "forbidden", error("$base/resources/log/records", "keeper"))
        val anonymous = client.get("$base/resources/parts/records")
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
        // The admin API mounts no page: its root is not a route.
        val root = client.get(base)
        assertEquals(HttpStatusCode.NotFound, root.status)
        assertTrue(root.contentType()?.match(ContentType.Text.Html) != true)
    }

    @Test
    fun `bulk delete reports each record and refuses callers without the role`() = testApplication {
        admin(storeSchema) { maxBulkDelete = 3 }
        seedStore()
        val forbidden = client.postJson("$base/resources/parts/bulk-delete", """{"ids": ["p1", "p2"]}""", "keeper")
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        assertEquals(4, ids("$base/resources/parts/records", "keeper").size)

        val result = client.postJson("$base/resources/parts/bulk-delete", """{"ids": ["p1", "missing", "p2", "p1"]}""", "manager")
        assertEquals(HttpStatusCode.OK, result.status)
        val body = AdminJson.decodeFromString(BulkDeleteResult.serializer(), result.bodyAsText())
        assertEquals(listOf("p1", "p2"), body.deleted)
        assertEquals("missing", body.failed.single().id)
        assertEquals(io.github.basekt.server.ErrorCode.NOT_FOUND, body.failed.single().error.error)

        assertEquals(HttpStatusCode.BadRequest, client.postJson("$base/resources/parts/bulk-delete", """{"ids": []}""", "manager").status)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("$base/resources/parts/bulk-delete", """{"ids": ["a", "b", "c", "d"]}""", "manager").status)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("$base/resources/parts/bulk-delete", """{"id": "a"}""", "manager").status)
    }

    @Test
    fun `CSV export follows the list's filters and is safe to open in a spreadsheet`() = testApplication {
        admin(storeSchema) {
            defaultPageSize = 2
            maxPageSize = 2
        }
        seedStore()
        val response = client.get("$base/resources/parts/export.csv?sort=name") { asUser("keeper") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Text.CSV, response.contentType()?.withoutParameters())
        assertTrue(response.headers[HttpHeaders.ContentDisposition]!!.contains("parts.csv"))
        val lines = response.bodyAsText().split("\r\n").filter { it.isNotEmpty() }
        assertEquals(
            listOf(
                "id,name,note,quantity,cost,countedAt,binId",
                "p4,'=SUM(A1),Bolt cutter,2,19.9,2026-10-01T08:00:00Z,",
                "p1,Bolt,,120,0.1,,b1",
                "p2,Nut,\"metric, \"\"M6\"\"\",80,0.05,,b1",
                "p3,Washer,,0,0.02,,",
            ),
            lines,
            "rows come from several pages of two",
        )
        val filtered = client.get("$base/resources/parts/export.csv?search=bolt&sort=id") { asUser("keeper") }.bodyAsText()
        assertEquals(3, filtered.split("\r\n").count { it.isNotEmpty() })

        val refused = client.get("$base/resources/log/export.csv") { asUser("keeper") }
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertEquals("forbidden", refused.json()["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an export larger than the limit is refused before it starts`() = testApplication {
        admin(storeSchema) { maxExportRows = 3 }
        seedStore()
        val response = client.get("$base/resources/parts/export.csv") { asUser("keeper") }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_query", response.json()["error"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, client.get("$base/resources/parts/export.csv?quantity[gt]=50") { asUser("keeper") }.status)
    }

    @Test
    fun `a public schema is open to anonymous callers, and the path comes from configuration`() = testApplication {
        environment { config = MapApplicationConfig("basekt.admin.path" to "/back-office") }
        admin(glossarySchema)
        val schema = client.get("/back-office/schema").json()
        val terms = schema["resources"]!!.jsonArray.single().jsonObject
        assertEquals("terms", terms["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("read"), terms["operations"]!!.jsonArray.map { it.jsonPrimitive.content })
        // Signed in, but without the editor role.
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/back-office/resources/terms/records", """{"word": "x"}""").status)
        assertEquals(HttpStatusCode.Created, client.postJson("/back-office/resources/terms/records", """{"word": "idempotent"}""", "editor").status)
        assertEquals(1, client.get("/back-office/resources/terms/records?search=IDEM").json()["total"]!!.jsonPrimitive.long)
    }

    @Test
    fun `admin routes resolve callers through Ktor authentication too`() = testApplication {
        val jwt = JwtSettings("tests", "basekt-tests", "a-test-secret-that-is-long-enough")
        application {
            install(Authentication) { baseKtJwt("jwt", jwt) }
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = glossarySchema
                identities = AuthIdentities("jwt")
            }
            install(BaseKtAdmin)
        }
        val editor = jwt.issue(Identity("eve", setOf("editor")), 1.hours)
        val schema = client.get("$base/schema") { bearerAuth(editor) }.json()
        assertEquals("eve", schema["caller"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        val bad = client.get("$base/schema") { bearerAuth("not-a-token") }
        assertEquals(HttpStatusCode.Unauthorized, bad.status)
        assertEquals("unauthorized", bad.json()["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `install checks its order and settings`() {
        assertFailsWith<IllegalStateException> {
            testApplication { application { install(BaseKtAdmin) }; client.get("/") }
        }
        assertFailsWith<IllegalArgumentException> {
            testApplication { admin(glossarySchema) { path = "/api" }; client.get("/") }
        }
    }
}
