package io.github.basekt.openapi

import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.ActionTarget
import io.github.basekt.schema.OnDelete
import io.github.basekt.schema.Operation
import io.github.basekt.schema.Schema
import io.github.basekt.schema.schema
import io.github.basekt.server.BaseKt
import io.github.basekt.server.BaseKtConfig
import io.github.basekt.server.Identity
import io.github.basekt.server.IdentityResolver
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A made-up tool library: keepers look after tools, anyone browses them, signed-in members borrow them and see only
 * their own loans. Loans can't be deleted by anyone.
 */
val workshopSchema: Schema = schema {
    resource("tools") {
        string("name") { required(); length(min = 1, max = 40) }
        string("code") { required(); unique(); pattern("[A-Z]{2}-[0-9]{3}", "Two capitals, a dash and three digits.") }
        enum("condition", listOf("good", "worn", "broken")) { required() }
        int("stock") { min(0); max(500) }
        decimal("deposit") { min("0.5") }
        bool("powered")
        instant("checkedAt")
        int("revision") { version() }
        hasMany("loans", "loans", foreignField = "toolId")
        action("repair") {
            input {
                string("note") { required(); length(max = 200) }
                int("hours") { min(1) }
            }
            allow("keeper")
            confirm("Send it for repair?")
        }
        action("recount", ActionTarget.RESOURCE) { allow("keeper") }
        permissions { read(everyone); write("keeper") }
    }
    resource("loans") {
        belongsTo("tool", "tools") { required() }
        string("borrower") { createdBy() }
        instant("lentAt") { createdAt() }
        permissions {
            read(authenticated); create(authenticated); update("keeper")
            ownedBy("borrower", on = setOf(Operation.READ), unless = setOf("keeper"))
        }
    }
}

/**
 * A made-up permit office: residents file permits, clerks grant or refuse them through a workflow, and clerks keep
 * private notes. Permits are numbered by the server and keep a hidden field. Inspectors have names for ids, and a permit
 * is inspected once by each inspector.
 */
val permitSchema: Schema = schema {
    resource("permits") {
        string("summary") { required(); markdown { math() } }
        string("number") { sequence(prefix = "P-", digits = 4) }
        file("plan", "plans")
        string("internalRef") { hidden() }
        long("area") { required(); min(1) }
        enum("status", listOf("draft", "filed", "granted", "refused"))
        workflow("status", initial = "draft") {
            transition("submit", from = "draft", to = "filed") { allow(authenticated) }
            transition("grant", from = "filed", to = "granted") {
                allow("clerk")
                input { string("reasons") { list(); required() } }
            }
            transition("refuse", from = listOf("draft", "filed"), to = "refused") { allow("clerk") }
        }
        hasMany("notes", "notes", foreignField = "permitId")
        permissions { read(authenticated); create(authenticated); update("clerk") }
    }
    resource("notes") {
        string("text") { required() }
        belongsTo("permit", "permits") { required() }
        permissions { read("clerk"); write("clerk") }
    }
    resource("inspectors") {
        naturalId { length(min = 3, max = 12); pattern("[a-z]+") }
        string("name")
        permissions { read("clerk"); write("clerk") }
    }
    resource("inspections") {
        belongsTo("permit", "permits", onDelete = OnDelete.CASCADE) { required() }
        belongsTo("inspector", "inspectors") { required() }
        instant("at")
        keyedBy("permitId", "inspectorId")
        permissions { read("clerk"); write("clerk") }
    }
}

/** Test-only identities: `X-User` names the caller and `X-Roles` lists roles. No header means anonymous. */
val headerIdentities: IdentityResolver = IdentityResolver { call ->
    call.request.headers["X-User"]?.let { user ->
        Identity(user, call.request.headers["X-Roles"].orEmpty().split(',').filter { it.isNotBlank() }.toSet())
    }
}

/** Installs BaseKt over [schema] on an in-memory data source, then [BaseKtOpenApi]. */
fun ApplicationTestBuilder.openApi(
    schema: Schema,
    baseKt: BaseKtConfig.() -> Unit = {},
    openApi: BaseKtOpenApiConfig.() -> Unit = {},
) {
    application {
        install(BaseKt) {
            dataSource = InMemoryDataSource()
            this.schema = schema
            identities = headerIdentities
            baseKt()
        }
        install(BaseKtOpenApi, openApi)
    }
}

fun HttpRequestBuilder.asUser(vararg roles: String) {
    header("X-User", "u1")
    header("X-Roles", roles.joinToString(","))
}

suspend fun HttpClient.document(path: String = "/openapi.json"): JsonObject = Json.parseToJsonElement(get(path).bodyAsText()).jsonObject

/**
 * Checks documents against the OpenAPI 3.1 schema, and bodies against a document's component schemas. The schema in
 * `oas-3.1-schema.json` is the OpenAPI Initiative's own, from https://spec.openapis.org/oas/3.1/schema/2022-10-07
 * (Apache-2.0).
 */
object OpenApiValidation {
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    private val config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build()
    private val openApi31 = factory.getSchema(javaClass.getResourceAsStream("/oas-3.1-schema.json")!!, config)

    /** What the official OpenAPI 3.1 schema finds wrong with [document]. */
    fun documentProblems(document: String): List<String> = openApi31.validate(document, InputFormat.JSON).map { it.message }

    /** What [document]'s schema [component] finds wrong with [body]. */
    fun bodyProblems(document: JsonObject, component: String, body: String): List<String> {
        val wrapper = buildJsonObject {
            put("\$schema", JsonPrimitive("https://json-schema.org/draft/2020-12/schema"))
            put("\$ref", JsonPrimitive("#/components/schemas/$component"))
            put("components", document.getValue("components"))
        }
        return factory.getSchema(wrapper.toString(), config).validate(body, InputFormat.JSON).map { it.message }
    }
}
