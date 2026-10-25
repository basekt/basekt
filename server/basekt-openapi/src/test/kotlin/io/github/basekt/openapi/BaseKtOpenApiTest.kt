package io.github.basekt.openapi

import io.github.basekt.server.BaseKtConfig
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** The code of the workshop's actions: a repair answers with the record, a recount with its own JSON. */
private val workshopActions: BaseKtConfig.() -> Unit = {
    actions {
        on("tools", "repair") { null }
        on("tools", "recount") { buildJsonObject { put("counted", 0) } }
    }
}

private fun JsonElement.at(vararg keys: String): JsonElement = keys.fold(this) { node, key ->
    (node as? JsonObject)?.get(key) ?: fail("No '$key' in ${keys.joinToString(".")}: $node")
}

private fun JsonElement.has(vararg keys: String): Boolean = runCatching { at(*keys) }.isSuccess

private fun JsonElement.strings(): List<String> = jsonArray.map { it.jsonPrimitive.content }

class BaseKtOpenApiTest {
    @Test
    fun `both schemas' documents are valid OpenAPI 3_1`() = testApplication {
        openApi(workshopSchema, workshopActions) { security = OpenApiSecurity.Bearer("JWT") }
        val workshop = client.get("/openapi.json").bodyAsText()
        assertEquals(emptyList(), OpenApiValidation.documentProblems(workshop))
        assertEquals(OPENAPI_VERSION, client.document().at("openapi").jsonPrimitive.content)
    }

    @Test
    fun `the permit office's document is valid OpenAPI 3_1 too`() = testApplication {
        openApi(permitSchema, { basePath = "/v2" }) { security = OpenApiSecurity.ApiKey("X-Api-Key") }
        assertEquals(emptyList(), OpenApiValidation.documentProblems(client.get("/openapi.json").bodyAsText()))
    }

    @Test
    fun `the validator does reject a broken document`() {
        val broken = permitSchema.toOpenApi().let { JsonObject(it - "info") }.toOpenApiJson()
        assertTrue(OpenApiValidation.documentProblems(broken).isNotEmpty())
    }

    @Test
    fun `every route of a resource is described`() = testApplication {
        openApi(workshopSchema, workshopActions)
        val paths = client.document().at("paths")
        assertEquals(setOf("get", "post"), paths.at("/api/tools").jsonObject.keys - "parameters")
        assertEquals(setOf("get", "patch", "delete"), paths.at("/api/tools/{id}").jsonObject.keys - "parameters")
        assertTrue(paths.has("/api/tools/{id}/loans", "get"))
        assertTrue(paths.has("/api/loans/{id}/tool", "get"))
        assertTrue(paths.has("/api/tools/{id}/actions/repair", "post"))
        assertTrue(paths.has("/api/tools/actions/recount", "post"))
        assertTrue(paths.has("/api/aggregate/tools", "get"))
        assertTrue(paths.has("/api/schema", "get"))
        assertEquals("tools.action.repair", paths.at("/api/tools/{id}/actions/repair", "post", "operationId").jsonPrimitive.content)
    }

    @Test
    fun `operations no one may run are left out, and hidden fields too`() = testApplication {
        openApi(workshopSchema, workshopActions)
        val document = client.document()
        assertEquals(setOf("get", "patch"), document.at("paths", "/api/loans/{id}").jsonObject.keys - "parameters")
    }

    @Test
    fun `hidden fields are left out everywhere`() = testApplication {
        openApi(permitSchema)
        val permits = client.document()
        assertFalse(permits.has("paths", "/api/permits/{id}", "delete"))
        assertFalse(permits.has("components", "schemas", "permits", "properties", "internalRef"))
        assertFalse("internalRef" in client.get("/openapi.json").bodyAsText())
    }

    @Test
    fun `fields carry their types and checks`() = testApplication {
        openApi(workshopSchema, workshopActions)
        val schemas = client.document().at("components", "schemas")
        val tool = schemas.at("tools", "properties")
        assertEquals(listOf("id", "name", "code", "condition"), schemas.at("tools", "required").strings())
        assertEquals(JsonPrimitive(1), tool.at("name", "minLength"))
        assertEquals(JsonPrimitive(40), tool.at("name", "maxLength"))
        assertEquals("^(?:[A-Z]{2}-[0-9]{3})$", tool.at("code", "pattern").jsonPrimitive.content)
        assertEquals(listOf("good", "worn", "broken"), tool.at("condition", "enum").strings())
        assertEquals(listOf("integer", "null"), tool.at("stock", "type").strings())
        assertEquals("500", tool.at("stock", "maximum").toString())
        assertEquals("string", tool.at("deposit", "type").strings().first())
        assertEquals("date-time", tool.at("checkedAt", "format").jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), tool.at("revision", "readOnly"))

        val create = schemas.at("tools.create")
        assertEquals(listOf("name", "code", "condition"), create.at("required").strings())
        assertEquals(JsonPrimitive(false), create.at("additionalProperties"))
        assertFalse(create.has("properties", "revision"))
        assertEquals(listOf("string", "number", "null"), create.at("properties", "deposit", "type").strings())
        assertEquals("0.5", create.at("properties", "deposit", "minimum").toString())
        assertTrue(schemas.has("tools.patch", "properties", "revision"))
        assertFalse(schemas.has("tools.patch", "required"))

        val loan = schemas.at("loans")
        assertEquals(listOf("id", "toolId"), loan.at("required").strings())
        assertTrue("'tools'" in loan.at("properties", "toolId", "description").jsonPrimitive.content)
        assertEquals(setOf("toolId"), schemas.at("loans.create", "properties").jsonObject.keys - "id")
    }

    @Test
    fun `workflows, list inputs, markdown, files and sequences`() = testApplication {
        openApi(permitSchema)
        val document = client.document()
        val permit = document.at("components", "schemas", "permits", "properties")
        assertEquals(listOf("draft", "filed", "granted", "refused", null), permit.at("status", "enum").jsonArray.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content })
        assertEquals(JsonPrimitive(true), permit.at("status", "readOnly"))
        assertEquals("text/markdown", permit.at("summary", "contentMediaType").jsonPrimitive.content)
        assertEquals("^plans(/[A-Za-z0-9][A-Za-z0-9._-]*)+$", permit.at("plan", "pattern").jsonPrimitive.content)
        assertFalse(document.has("components", "schemas", "permits.create", "properties", "status"))
        assertFalse(document.has("components", "schemas", "permits.create", "properties", "number"))

        val grant = document.at("components", "schemas", "permits.grant.input")
        assertEquals("array", grant.at("properties", "reasons", "type").jsonPrimitive.content)
        assertEquals(JsonPrimitive(1), grant.at("properties", "reasons", "minItems"))
        val refuse = document.at("paths", "/api/permits/{id}/actions/refuse", "post")
        assertTrue("from `draft` or `filed` to `refused`" in refuse.at("description").jsonPrimitive.content)
        assertNull(refuse.jsonObject["requestBody"])
        assertTrue(refuse.has("responses", "409"))
        assertTrue(document.has("paths", "/api/permits/{id}/actions/grant", "post", "requestBody"))
    }

    @Test
    fun `list routes take the filters, sort, limit and cursor`() = testApplication {
        openApi(workshopSchema, workshopActions)
        val document = client.document()
        val list = document.at("paths", "/api/tools", "get")
        val parameters = list.at("parameters").jsonArray.map { parameter ->
            val ref = (parameter as JsonObject)["\$ref"]?.jsonPrimitive?.content
            if (ref == null) parameter else document.at("components", "parameters", ref.substringAfterLast('/'))
        }
        val names = parameters.map { it.at("name").jsonPrimitive.content }
        for (name in listOf("id", "name", "name[contains]", "stock[gte]", "deposit[lt]", "condition[in]", "powered[null]", "sort")) {
            assertTrue(name in names, "$name in $names")
        }
        assertFalse("powered[gt]" in names)
        assertFalse("stock[contains]" in names)
        assertEquals(listOf("limit", "cursor"), names.takeLast(2))
        assertEquals("form", document.at("components", "parameters", "tools.condition.in", "style").jsonPrimitive.content)
        // The relation route to loans takes the loans' filters, kept once.
        assertTrue(document.has("paths", "/api/tools/{id}/loans", "get", "parameters"))
        assertTrue(document.has("components", "parameters", "loans.toolId.in"))
    }

    @Test
    fun `page sizes and the request id header follow BaseKt's settings`() = testApplication {
        openApi(workshopSchema, { workshopActions(); defaultPageSize = 7; maxPageSize = 30; requestIdHeader = "X-Trace" })
        val document = client.document()
        assertEquals(JsonPrimitive(7), document.at("components", "parameters", "basekt.limit", "schema", "default"))
        assertEquals(JsonPrimitive(30), document.at("components", "parameters", "basekt.limit", "schema", "maximum"))
        assertTrue(document.has("paths", "/api/tools", "get", "responses", "200", "headers", "X-Trace"))
    }

    @Test
    fun `access decides the auth answers and the security requirement`() = testApplication {
        openApi(workshopSchema, workshopActions) { security = OpenApiSecurity.Bearer("JWT") }
        val document = client.document()
        val list = document.at("paths", "/api/tools", "get")
        assertFalse(list.has("responses", "401"))
        assertEquals(2, list.at("security").jsonArray.size)
        val create = document.at("paths", "/api/tools", "post")
        assertTrue(create.has("responses", "401") && create.has("responses", "403"))
        assertEquals(1, create.at("security").jsonArray.size)
        assertTrue("keeper" in create.at("description").jsonPrimitive.content)
        val loans = document.at("paths", "/api/loans", "get")
        assertTrue(loans.has("responses", "401"))
        assertTrue(loans.has("responses", "403"), "the owner scope can refuse")
        assertEquals("bearer", document.at("components", "securitySchemes", "basekt.auth", "scheme").jsonPrimitive.content)
    }

    @Test
    fun `what the server answers fits the document`() = testApplication {
        openApi(workshopSchema, workshopActions)
        val document = client.document()
        fun check(component: String, body: String) = assertEquals(emptyList(), OpenApiValidation.bodyProblems(document, component, body), body)
        suspend fun post(path: String, body: String, vararg roles: String): HttpResponse = client.post(path) {
            asUser(*roles)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        val toolBody = """{"name":"Drill","code":"DR-001","condition":"good","stock":3,"deposit":"12.50","powered":true}"""
        check("tools.create", toolBody)
        val created = post("/api/tools", toolBody, "keeper")
        assertEquals(HttpStatusCode.Created, created.status)
        val tool = created.bodyAsText()
        check("tools", tool)
        check("tools.page", client.get("/api/tools").bodyAsText())
        val id = Json.parseToJsonElement(tool).at("id").jsonPrimitive.content

        val loan = post("/api/loans", """{"toolId":"$id"}""", "member")
        check("loans", loan.bodyAsText())
        check("loans.page", client.get("/api/tools/$id/loans") { asUser("keeper") }.bodyAsText())

        val repaired = post("/api/tools/$id/actions/repair", """{"note":"Chuck sticks"}""", "keeper")
        assertEquals(HttpStatusCode.OK, repaired.status)
        check("tools", repaired.bodyAsText())

        val invalid = post("/api/tools", """{"name":"","code":"nope","condition":"lost"}""", "keeper")
        assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
        check("basekt.ApiError", invalid.bodyAsText())
        assertTrue(OpenApiValidation.bodyProblems(document, "tools.create", """{"name":"","code":"nope","condition":"lost"}""").isNotEmpty())
        check("basekt.ApiError", client.get("/api/loans").bodyAsText())
        check("basekt.AggregatePage", client.get("/api/aggregate/tools?count&groupBy=condition").bodyAsText())
    }

    @Test
    fun `an application merges in the routes it adds`() = testApplication {
        val health = """{"paths":{"/health":{"get":{"operationId":"health","responses":{"200":{"description":"Up."}}}}},"tags":[{"name":"ops"}]}"""
        openApi(workshopSchema, workshopActions) { merge(health) }
        val document = client.document()
        assertTrue(document.has("paths", "/health", "get"))
        assertTrue(document.has("paths", "/api/tools", "get"))
        assertTrue("ops" in document.at("tags").jsonArray.map { it.at("name").jsonPrimitive.content })
        assertEquals(emptyList(), OpenApiValidation.documentProblems(client.get("/openapi.json").bodyAsText()))
    }

    @Test
    fun `a merge that redefines a route or a component is refused`() {
        val document = workshopSchema.toOpenApi()
        val clash = Json.parseToJsonElement(
            """{"paths":{"/api/tools":{"get":{"responses":{}}}},"components":{"schemas":{"tools":{"type":"string"}}}}""",
        ).jsonObject
        val error = runCatching { document.mergeOpenApi(clash) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue("GET /api/tools" in error.message!!, error.message)
        assertTrue("components.schemas.tools" in error.message!!, error.message)
        // The same path with another method, and an identical component, merge fine.
        val more = Json.parseToJsonElement(
            """{"paths":{"/api/tools":{"put":{"responses":{}}}},"components":{"schemas":{"basekt.FieldErrorCode":${document.at("components", "schemas", "basekt.FieldErrorCode")}}}}""",
        ).jsonObject
        assertTrue(document.mergeOpenApi(more).has("paths", "/api/tools", "put"))
    }

    @Test
    fun `the path, title and servers come from the configuration`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "basekt.openapi.path" to "/v2/openapi.json",
                "basekt.openapi.title" to "Permit office",
                "basekt.openapi.version" to "4.2.0",
            ).apply { put("basekt.openapi.servers", listOf("https://permits.example.org")) }
        }
        openApi(permitSchema, { basePath = "/v2" })
        assertEquals(HttpStatusCode.NotFound, client.get("/openapi.json").status)
        val document = client.document("/v2/openapi.json")
        assertEquals("Permit office", document.at("info", "title").jsonPrimitive.content)
        assertEquals("4.2.0", document.at("info", "version").jsonPrimitive.content)
        assertEquals("https://permits.example.org", document.at("servers").jsonArray.single().at("url").jsonPrimitive.content)
        assertTrue(document.has("paths", "/v2/permits", "get"))
    }

    @Test
    fun `install refuses a path that is one of BaseKt's routes, and a missing BaseKt`() {
        val clash = runCatching {
            testApplication {
                openApi(permitSchema) { path = "/api/permits" }
                startApplication()
            }
        }.exceptionOrNull()
        assertTrue(generateSequence(clash) { it.cause }.any { "one of BaseKt's routes" in it.message.orEmpty() }, clash.toString())

        val alone = runCatching {
            testApplication {
                application { install(BaseKtOpenApi) }
                startApplication()
            }
        }.exceptionOrNull()
        assertTrue(generateSequence(alone) { it.cause }.any { "Install BaseKt before BaseKtOpenApi" in it.message.orEmpty() }, alone.toString())
    }

    @Test
    fun `invalid options list every problem`() {
        val error = runCatching {
            permitSchema.toOpenApi(OpenApiOptions(title = " ", basePath = "api", defaultPageSize = 0, servers = listOf("ftp://x")))
        }.exceptionOrNull()
        val message = error?.message.orEmpty()
        for (part in listOf("title", "basePath", "Page sizes", "ftp://x")) assertTrue(part in message, message)
    }

    @Test
    fun `the document is the same on every call and for every caller`() = testApplication {
        openApi(workshopSchema, workshopActions)
        val anonymous = client.get("/openapi.json").bodyAsText()
        val keeper = client.get("/openapi.json") { asUser("keeper") }.bodyAsText()
        assertEquals(anonymous, keeper)
        assertEquals(workshopSchema.toOpenApi().toOpenApiJson(), anonymous)
    }

    @Test
    fun `options survive their JSON form`() {
        val options = OpenApiOptions(title = "Tools", servers = listOf("/"), security = OpenApiSecurity.ApiKey("X-Key"))
        val json = Json.encodeToString(OpenApiOptions.serializer(), options)
        assertEquals(options, Json.decodeFromString(OpenApiOptions.serializer(), json))
        assertTrue("\"apiKey\"" in json, json)
    }
}

private val Json = kotlinx.serialization.json.Json
