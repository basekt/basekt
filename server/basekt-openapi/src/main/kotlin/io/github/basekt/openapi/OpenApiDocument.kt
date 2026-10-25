package io.github.basekt.openapi

import io.github.basekt.schema.Access
import io.github.basekt.schema.Action
import io.github.basekt.schema.ActionTarget
import io.github.basekt.schema.Auto
import io.github.basekt.schema.Field
import io.github.basekt.schema.FieldErrorCode
import io.github.basekt.schema.FieldType
import io.github.basekt.schema.IdSource
import io.github.basekt.schema.IdSpec
import io.github.basekt.schema.Operation
import io.github.basekt.schema.Relation
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Rule
import io.github.basekt.schema.Schema
import io.github.basekt.server.ErrorCode
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import java.math.BigDecimal

/** The OpenAPI version of every document [toOpenApi] writes. */
public const val OPENAPI_VERSION: String = "3.1.0"

/**
 * Describes the REST API BaseKt serves for this schema as an OpenAPI [OPENAPI_VERSION] document: every resource's
 * list, get, create, patch and delete, its relation routes, its actions and workflow transitions, the aggregate
 * route and `GET /schema`, with the fields' types and checks as JSON Schema and the typed error bodies.
 *
 * The document is the full one, the same for every caller. An operation no role may run (open to nobody) is left
 * out, since no caller can use it, and so are hidden fields. Every other operation is in, with the access it needs in
 * its description and the `401` and `403` answers it may give.
 *
 * Component names are prefixed so they can't clash: a resource's record is `products`, its bodies `products.create`
 * and `products.patch`, its page `products.page`, an action's input `products.lend.input`, and BaseKt's own types
 * `basekt.ApiError` and so on. Operation ids are `products.list`, `products.get`, `products.relation.category`,
 * `products.action.lend` and so on.
 *
 * Throws [IllegalArgumentException] when [options] have [problems][OpenApiOptions.problems].
 */
public fun Schema.toOpenApi(options: OpenApiOptions = OpenApiOptions()): JsonObject {
    val problems = options.problems()
    require(problems.isEmpty()) { "The OpenAPI options are not valid:\n" + problems.joinToString("\n") { "- $it" } }
    return OpenApiWriter(this, options).document()
}

@OptIn(ExperimentalSerializationApi::class)
private val PrettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

/** Renders this document as pretty-printed JSON ending in a newline: the text to serve, or to check in. */
public fun JsonObject.toOpenApiJson(): String = PrettyJson.encodeToString(JsonObject.serializer(), this) + "\n"

/** What a field's schema is written for: a record the server sends, a create's body or a patch's body. */
private enum class Mode { OUTPUT, CREATE, PATCH }

/** How far an [Access] lets callers in. */
private enum class Gate { OPEN, SIGNED_IN, ROLES, CLOSED }

private fun Access.gate(): Gate = when (this) {
    Access.Everyone -> Gate.OPEN
    Access.Authenticated -> Gate.SIGNED_IN
    is Access.Roles -> if (roles.isEmpty()) Gate.CLOSED else Gate.ROLES
    Access.Nobody -> Gate.CLOSED
}

private fun Access.sentence(): String = when (this) {
    Access.Everyone -> "Open to everyone."
    Access.Authenticated -> "Needs a signed-in caller."
    is Access.Roles -> "Needs one of the roles: ${roles.sorted().joinToString()}."
    Access.Nobody -> "Open to no one."
}

private const val SCHEMAS = "#/components/schemas/"
private const val RESPONSES = "#/components/responses/"
private const val PARAMETERS = "#/components/parameters/"
private const val SECURITY = "basekt.auth"
private const val JSON = "application/json"
private const val DECIMAL_PATTERN = """^-?\d+(\.\d+)?$"""

private val errorResponses = linkedMapOf(
    400 to ("basekt.badRequest" to "The body isn't a JSON object (`bad_request`), or a filter, sort, limit or cursor is invalid (`invalid_query`)."),
    401 to ("basekt.unauthorized" to "The operation needs a signed-in caller (`unauthorized`)."),
    403 to ("basekt.forbidden" to "The caller's roles or record scope don't allow it (`forbidden`)."),
    404 to ("basekt.notFound" to "The record or relation doesn't exist, or is outside the caller's scope (`not_found`)."),
    409 to ("basekt.conflict" to "The id is taken, the record changed since the version sent, the transition can't start from the record's state, or records that belong to it restrict its delete (`conflict`)."),
    422 to ("basekt.unprocessable" to "A field was rejected (`validation_failed`, see `fields`) or a hook refused the write (`rejected`)."),
    500 to ("basekt.internalError" to "Something failed on the server (`internal_error`). The message names the request id the server logged it with."),
    501 to ("basekt.notImplemented" to "The data source can't do what the request needs, such as aggregates (`not_implemented`)."),
    503 to ("basekt.unavailable" to "The signed-in caller's roles from the server couldn't be looked up; try again later (`unavailable`)."),
)

private class OpenApiWriter(private val schema: Schema, private val options: OpenApiOptions) {
    private val base = options.basePath.trimEnd('/')
    private val paths = linkedMapOf<String, MutableMap<String, JsonElement>>()
    private val schemas = linkedMapOf<String, JsonElement>()
    private val filters = linkedMapOf<String, JsonElement>()
    private val tags = linkedSetOf<String>()

    fun document(): JsonObject {
        schemaRoute()
        for (resource in schema.resources) resourceRoutes(resource)
        return obj(
            "openapi" to str(OPENAPI_VERSION),
            "info" to obj("title" to str(options.title), "version" to str(options.version), "description" to options.description?.let(::str)),
            "servers" to options.servers.takeIf { it.isNotEmpty() }?.let { servers -> JsonArray(servers.map { obj("url" to str(it)) }) },
            "tags" to JsonArray(tags.map { obj("name" to str(it)) }),
            "paths" to JsonObject(paths.mapValues { (_, item) -> JsonObject(item) }),
            "components" to obj(
                "schemas" to JsonObject(baseKtSchemas() + schemas),
                "responses" to JsonObject(errorResponses.values.associate { (name, description) -> name to errorResponse(description) }),
                "parameters" to JsonObject(filters + obj(
                    "basekt.id" to obj("name" to str("id"), "in" to str("path"), "required" to JsonPrimitive(true), "description" to str("The record's id."), "schema" to obj("type" to str("string"))),
                    "basekt.cursor" to obj("name" to str("cursor"), "in" to str("query"), "description" to str("The previous page's `nextCursor`."), "schema" to obj("type" to str("string"))),
                    "basekt.limit" to obj(
                        "name" to str("limit"), "in" to str("query"), "description" to str("The page size."),
                        "schema" to obj("type" to str("integer"), "minimum" to JsonPrimitive(1), "maximum" to JsonPrimitive(options.maxPageSize), "default" to JsonPrimitive(options.defaultPageSize)),
                    ),
                )),
                "headers" to obj(
                    "basekt.requestId" to obj(
                        "description" to str("The call's request id, also in every error body and in the server's log lines about the call."),
                        "schema" to obj("type" to str("string")),
                    ),
                ),
                "securitySchemes" to options.security?.let { obj(SECURITY to securityScheme(it)) },
            ),
        )
    }

    private fun schemaRoute() {
        tags += "basekt"
        put(
            "$base/schema", "get",
            obj(
                "operationId" to str("basekt.schema"),
                "tags" to strings(listOf("basekt")),
                "summary" to str("The schema"),
                "description" to str("The whole schema as JSON, as `Schema.toJson` writes it. Open to everyone."),
                "responses" to obj("200" to response("The schema.", obj("type" to str("object"))), "500" to errorRef(500)),
            ),
        )
    }

    private fun resourceRoutes(resource: Resource) {
        val name = resource.name
        val path = "$base/$name"
        val permissions = resource.permissions
        schemas[name] = recordSchema(resource)
        val read = permissions.read
        if (read.gate() != Gate.CLOSED) {
            schemas["$name.page"] = pageSchema(name)
            put(path, "get", operation(resource, "list", "List $name", listOf(read), Operation.READ, queryParameters(resource, paging = true), null, mapOf("200" to response("A page of records.", ref("$name.page"))), setOf(400)))
            put("$path/{id}", "get", operation(resource, "get", "Get a record of $name", listOf(read), Operation.READ, emptyList(), null, mapOf("200" to response("The record.", ref(name))), setOf(404)))
            put(
                "$base/aggregate/$name", "get",
                operation(resource, "aggregate", "Aggregate $name", listOf(read), Operation.READ, aggregateParameters(resource), null, mapOf("200" to response("The groups.", ref("basekt.AggregatePage"))), setOf(400, 501)),
            )
        }
        if (permissions.create.gate() != Gate.CLOSED) {
            schemas["$name.create"] = bodySchema(resource, Mode.CREATE)
            put(
                path, "post",
                operation(
                    resource, "create", "Create a record of $name", listOf(permissions.create), Operation.CREATE, emptyList(), body(ref("$name.create"), required = true),
                    mapOf("201" to response("The created record.", ref(name), mapOf("Location" to obj("description" to str("The new record's path."), "schema" to obj("type" to str("string")))))),
                    setOf(400, 409, 422),
                ),
            )
        }
        if (permissions.update.gate() != Gate.CLOSED) {
            schemas["$name.patch"] = bodySchema(resource, Mode.PATCH)
            put(
                "$path/{id}", "patch",
                operation(resource, "update", "Patch a record of $name", listOf(permissions.update), Operation.UPDATE, emptyList(), body(ref("$name.patch"), required = true), mapOf("200" to response("The record as it is now.", ref(name))), setOf(400, 404, 409, 422)),
            )
        }
        if (permissions.delete.gate() != Gate.CLOSED) {
            put("$path/{id}", "delete", operation(resource, "delete", "Delete a record of $name", listOf(permissions.delete), Operation.DELETE, emptyList(), null, mapOf("204" to response("Deleted.")), setOf(404, 409, 422)))
        }
        for (relation in resource.relations) {
            val target = schema.resource(relation.target) ?: continue
            val access = listOf(read, target.permissions.read)
            if (access.any { it.gate() == Gate.CLOSED }) continue
            val (summary, parameters, ok) = when (relation) {
                is Relation.BelongsTo -> Triple("The ${target.name} record a record of $name belongs to", emptyList(), response("The related record.", ref(target.name)))
                is Relation.HasMany -> Triple("The ${target.name} records that belong to a record of $name", queryParameters(target, paging = true), response("A page of related records.", ref("${target.name}.page")))
            }
            if (relation is Relation.HasMany) schemas["${target.name}.page"] = pageSchema(target.name)
            put("$path/{id}/${relation.name}", "get", operation(resource, "relation.${relation.name}", summary, access, Operation.READ, parameters, null, mapOf("200" to ok), setOf(400, 404)))
        }
        val transitions = resource.workflow?.transitions.orEmpty().associateBy { it.name }
        for (action in resource.runnableActions) {
            if (action.access.gate() == Gate.CLOSED) continue
            val inputName = "$name.${action.name}.input"
            val body = if (action.input.isEmpty()) null else {
                schemas[inputName] = inputSchema(action)
                body(ref(inputName), required = action.input.any { it.required })
            }
            val transition = transitions[action.name]
            val record = action.target == ActionTarget.RECORD
            val summary = if (transition != null) "Transition '${action.name}' of a record of $name" else "Run '${action.name}' on ${if (record) "a record of " else ""}$name"
            val about = buildList {
                if (transition != null) add("Moves `${resource.workflow!!.field}` from ${transition.from.joinToString(" or ") { "`$it`" }} to `${transition.to}`.")
                if (record) add("The record must be in the caller's update scope.")
                action.confirm?.let { add("A panel asks first: \"$it\"") }
            }
            val responses = if (record) {
                mapOf("200" to response("The record as it is now, or the JSON the action's code answers with.", obj("anyOf" to JsonArray(listOf(ref(name), obj("type" to str("object")))))))
            } else {
                mapOf("200" to response("The JSON the action's code answers with.", obj("type" to str("object"))), "204" to response("The action ran and answered nothing."))
            }
            put(
                if (record) "$path/{id}/actions/${action.name}" else "$path/actions/${action.name}", "post",
                operation(resource, "action.${action.name}", summary, listOf(action.access), Operation.UPDATE.takeIf { record }, emptyList(), body, responses, setOfNotNull(400, if (record) 404 else null, 409, 422), about),
            )
        }
    }

    private fun put(path: String, method: String, operation: JsonObject) {
        val item = paths.getOrPut(path) { linkedMapOf() }
        if (path.contains("{id}") && "parameters" !in item) item["parameters"] = JsonArray(listOf(obj("\$ref" to str("${PARAMETERS}basekt.id"))))
        item[method] = operation
    }

    private fun operation(
        resource: Resource,
        id: String,
        summary: String,
        access: List<Access>,
        scopedBy: Operation?,
        parameters: List<JsonElement>,
        body: JsonObject?,
        responses: Map<String, JsonElement>,
        errors: Set<Int>,
        about: List<String> = emptyList(),
    ): JsonObject {
        tags += resource.name
        val gates = access.map { it.gate() }
        val scoped = scopedBy != null && resource.permissions.scopes.any { scopedBy in it.operations }
        val needsCaller = gates.any { it != Gate.OPEN }
        // Any call that resolves its caller may answer 503 when the server's own roles for them can't be looked up.
        val statuses = errors + setOfNotNull(401.takeIf { needsCaller }, 403.takeIf { Gate.ROLES in gates || scoped }, 500, 503)
        val description = (access.distinct().map { it.sentence() } + listOfNotNull("Narrowed to the caller's record scope.".takeIf { scoped }) + about).joinToString(" ")
        return obj(
            "operationId" to str("${resource.name}.$id"),
            "tags" to strings(listOf(resource.name)),
            "summary" to str(summary),
            "description" to str(description),
            "parameters" to parameters.takeIf { it.isNotEmpty() }?.let(::JsonArray),
            "requestBody" to body,
            "responses" to JsonObject(responses + statuses.sorted().associate { it.toString() to errorRef(it) }),
            "security" to options.security?.let {
                val auth = obj(SECURITY to JsonArray(emptyList()))
                JsonArray(if (needsCaller) listOf(auth) else listOf(JsonObject(emptyMap()), auth))
            },
        )
    }

    private fun response(description: String, schema: JsonObject? = null, headers: Map<String, JsonElement> = emptyMap()): JsonObject = obj(
        "description" to str(description),
        "headers" to JsonObject(mapOf(options.requestIdHeader to obj("\$ref" to str("#/components/headers/basekt.requestId"))) + headers),
        "content" to schema?.let { obj(JSON to obj("schema" to it)) },
    )

    private fun errorResponse(description: String): JsonObject = response(description, ref("basekt.ApiError"))

    private fun errorRef(status: Int): JsonObject = obj("\$ref" to str(RESPONSES + errorResponses.getValue(status).first))

    private fun body(schema: JsonObject, required: Boolean): JsonObject =
        obj("required" to JsonPrimitive(required), "content" to obj(JSON to obj("schema" to schema)))

    // Schemas

    private fun recordSchema(resource: Resource): JsonObject {
        val fields = resource.fields.filterNot { it.hidden }
        // A createdBy or updatedBy field stays unset for an anonymous caller, so it may be missing even if required.
        val alwaysSet = fields.filter { it.required && it.auto != Auto.CreatedBy && it.auto != Auto.UpdatedBy }
        return obj(
            "type" to str("object"),
            "required" to strings(listOf(Resource.ID) + alwaysSet.map { it.name }),
            "properties" to JsonObject(
                mapOf(Resource.ID to obj("type" to str("string"), "description" to str(idAbout(resource)))) +
                    fields.associate { it.name to fieldSchema(it, Mode.OUTPUT, nullable = it !in alwaysSet) },
            ),
        )
    }

    private fun idAbout(resource: Resource): String = when (resource.id.source) {
        IdSource.KEY -> "The record's id: its ${resource.id.key.joinToString(" and ")}, joined with '~', each with '%', '~' and '/' percent-encoded."
        else -> "The record's id."
    }

    private fun bodySchema(resource: Resource, mode: Mode): JsonObject {
        // An update may send the version it last read; every other automatic field is the server's.
        val fields = resource.fields.filter { !it.hidden && (it.auto == null || mode == Mode.PATCH && it.auto == Auto.Version) }
        val id = if (resource.id.source == IdSource.KEY) emptyMap() else mapOf(Resource.ID to idSchema(resource))
        val required = (if (resource.id.source == IdSource.NATURAL) listOf(Resource.ID) else emptyList()) +
            fields.filter { it.required }.map { it.name }
        return obj(
            "type" to str("object"),
            "required" to required.takeIf { mode == Mode.CREATE && it.isNotEmpty() }?.let(::strings),
            "properties" to JsonObject(
                (if (mode == Mode.CREATE) id else emptyMap()) + fields.associate { it.name to fieldSchema(it, mode, nullable = !it.required) },
            ),
            "additionalProperties" to JsonPrimitive(false),
        )
    }

    /** The id a create may send, or must for a natural id: every id's limits, narrowed by the resource's id rules. */
    private fun idSchema(resource: Resource): JsonObject {
        val lengths = resource.id.rules.filterIsInstance<Rule.Length>()
        val pattern = resource.id.rules.filterIsInstance<Rule.Pattern>().singleOrNull()
        val about = if (resource.id.source == IdSource.NATURAL) "The new record's id, which the client chooses. It never changes."
        else "The new record's id. The data source generates one when it is left out."
        return obj(
            "type" to str("string"),
            "minLength" to JsonPrimitive((lengths.mapNotNull { it.min } + 1).max()),
            "maxLength" to JsonPrimitive((lengths.mapNotNull { it.max } + IdSpec.MAX_LENGTH).min()),
            "pattern" to pattern?.let { str("^(?:${it.regex})$") },
            "description" to str(about),
        )
    }

    private fun inputSchema(action: Action): JsonObject = obj(
        "type" to str("object"),
        "required" to action.input.filter { it.required }.takeIf { it.isNotEmpty() }?.let { required -> strings(required.map { it.name }) },
        "properties" to JsonObject(action.input.associate { it.name to fieldSchema(it, Mode.CREATE, nullable = !it.required) }),
        "additionalProperties" to JsonPrimitive(false),
    )

    private fun pageSchema(name: String): JsonObject = obj(
        "type" to str("object"),
        "required" to strings(listOf("items", "nextCursor", "hasNext")),
        "properties" to obj(
            "items" to obj("type" to str("array"), "items" to ref(name)),
            "nextCursor" to obj("type" to strings(listOf("string", "null")), "description" to str("The cursor of the next page, or null on the last one.")),
            "hasNext" to obj("type" to str("boolean")),
        ),
    )

    private fun fieldSchema(field: Field, mode: Mode, nullable: Boolean): JsonObject {
        val value = valueSchema(field, mode)
        var schema: MutableMap<String, JsonElement> = if (field.list) {
            linkedMapOf("type" to str("array"), "items" to JsonObject(value)).also { if (field.required) it["minItems"] = JsonPrimitive(1) }
        } else {
            value
        }
        if (nullable) schema = nullable(schema)
        val description = fieldDescription(field, mode)
        if (description.isNotEmpty()) schema["description"] = str(description)
        if (mode == Mode.OUTPUT && field.auto != null) schema["readOnly"] = JsonPrimitive(true)
        return JsonObject(schema)
    }

    private fun valueSchema(field: Field, mode: Mode): MutableMap<String, JsonElement> {
        val schema = linkedMapOf<String, JsonElement>()
        val min = field.rules.filterIsInstance<Rule.Min>().map { number(it.value) }
        val max = field.rules.filterIsInstance<Rule.Max>().map { number(it.value) }
        fun bounds() {
            min.maxOrNull()?.let { schema["minimum"] = JsonPrimitive(it) }
            max.minOrNull()?.let { schema["maximum"] = JsonPrimitive(it) }
        }
        when (field.type) {
            FieldType.STRING -> {
                schema["type"] = str("string")
                val lengths = field.rules.filterIsInstance<Rule.Length>()
                lengths.mapNotNull { it.min }.maxOrNull()?.let { schema["minLength"] = JsonPrimitive(it) }
                val maxLength = (lengths.mapNotNull { it.max } + listOfNotNull(field.markdown?.maxLength)).minOrNull()
                maxLength?.let { schema["maxLength"] = JsonPrimitive(it) }
                field.rules.filterIsInstance<Rule.Pattern>().singleOrNull()?.let { schema["pattern"] = str("^(?:${it.regex})$") }
                if (field.markdown != null) schema["contentMediaType"] = str("text/markdown")
            }
            FieldType.INT, FieldType.LONG -> {
                schema["type"] = str("integer")
                schema["format"] = str(if (field.type == FieldType.INT) "int32" else "int64")
                bounds()
            }
            FieldType.DECIMAL -> if (mode == Mode.OUTPUT) {
                // Decimals are sent as strings so that no precision is lost.
                schema["type"] = str("string")
                schema["pattern"] = str(DECIMAL_PATTERN)
            } else {
                // A body may give a decimal as a number or a string: `pattern` checks strings, the bounds numbers.
                schema["type"] = strings(listOf("string", "number"))
                schema["pattern"] = str(DECIMAL_PATTERN)
                bounds()
            }
            FieldType.BOOL -> schema["type"] = str("boolean")
            FieldType.INSTANT -> {
                schema["type"] = str("string")
                schema["format"] = str("date-time")
            }
            FieldType.ENUM -> {
                schema["type"] = str("string")
                schema["enum"] = strings(field.values.orEmpty())
            }
            FieldType.REFERENCE -> schema["type"] = str("string")
            FieldType.FILE -> {
                schema["type"] = str("string")
                schema["maxLength"] = JsonPrimitive(1024)
                schema["pattern"] = str("^${jsEscape(field.bucket.orEmpty())}(/[A-Za-z0-9][A-Za-z0-9._-]*)+$")
            }
        }
        return schema
    }

    private fun fieldDescription(field: Field, mode: Mode): String = buildList {
        when (field.type) {
            FieldType.REFERENCE -> add("The id of a '${field.target}' record.")
            FieldType.FILE -> add("The key of a file in the '${field.bucket}' bucket.")
            FieldType.DECIMAL -> {
                add(if (mode == Mode.OUTPUT) "A decimal number, as a string." else "A decimal number, as a number or a string.")
                field.rules.filterIsInstance<Rule.Min>().forEach { add("At least ${it.value}.") }
                field.rules.filterIsInstance<Rule.Max>().forEach { add("At most ${it.value}.") }
            }
            else -> {}
        }
        field.markdown?.let { add("Markdown${if (it.math) ", with TeX formulas" else ""}${if (it.images) ", with images" else ""}; no raw HTML.") }
        field.rules.filterIsInstance<Rule.Pattern>().forEach { rule -> rule.message?.let { add(it) } }
        field.rules.filterIsInstance<Rule.Custom>().forEach { add("Checked by the server's '${it.name}' validator.") }
        if (field.unique) add("No two records hold the same value.")
        when (val auto = field.auto) {
            null -> {}
            Auto.CreatedAt -> add("Set by the server when the record is created.")
            Auto.UpdatedAt -> add("Set by the server on every write.")
            Auto.CreatedBy -> add("The id of the caller who created the record, set by the server.")
            Auto.UpdatedBy -> add("The id of the caller who last wrote the record, set by the server.")
            Auto.State -> add("The workflow's state, set by the server: only transitions change it.")
            Auto.Version -> add(
                if (mode == Mode.PATCH) "The version you last read: the update is refused with 409 if the record was written since."
                else "How many times the record was written, set by the server.",
            )
            is Auto.Inherited -> add("Copied by the server from the '${auto.field}' of the record '${auto.via}' points to.")
            is Auto.Sequence -> add("Numbered by the server.")
        }
    }.joinToString(" ")

    private fun nullable(schema: MutableMap<String, JsonElement>): MutableMap<String, JsonElement> {
        val type = schema["type"]
        schema["type"] = when (type) {
            is JsonArray -> JsonArray(type + str("null"))
            else -> JsonArray(listOfNotNull(type, str("null")))
        }
        (schema["enum"] as? JsonArray)?.let { schema["enum"] = JsonArray(it + JsonNull) }
        return schema
    }

    // Query parameters

    private fun filterable(resource: Resource): List<Field> =
        listOf(Field(Resource.ID, FieldType.STRING)) + resource.fields.filter { !it.hidden && !it.list }

    /**
     * The `field=` and `field[op]=` filters of [resource], as references to parameters kept once in the components
     * (`products.price.gte`), since a resource's list, relation and aggregate routes all take them.
     */
    private fun filterParameters(resource: Resource): List<JsonElement> = filterable(resource).flatMap { field ->
        val value = parameterSchema(field)
        val name = field.name
        val text = field.type in setOf(FieldType.STRING, FieldType.ENUM, FieldType.REFERENCE)
        buildList {
            fun filter(op: String?, description: String, schema: JsonObject, explode: Boolean = true) {
                val key = listOfNotNull(resource.name, name, op).joinToString(".")
                filters.getOrPut(key) { queryParameter(if (op == null) name else "$name[$op]", description, schema, explode) }
                add(obj("\$ref" to str(PARAMETERS + key)))
            }
            filter(null, "Keeps records whose `$name` equals the value.", value)
            filter("ne", "Keeps records whose `$name` differs from the value.", value)
            if (field.type != FieldType.BOOL) {
                for ((op, words) in listOf("gt" to "is greater than", "gte" to "is at least", "lt" to "is less than", "lte" to "is at most")) {
                    filter(op, "Keeps records whose `$name` $words the value.", value)
                }
            }
            filter("in", "Keeps records whose `$name` is one of the comma-separated values.", obj("type" to str("array"), "items" to value), explode = false)
            if (text) filter("contains", "Keeps records whose `$name` contains the text.", obj("type" to str("string")))
            filter("null", "Keeps records whose `$name` is (`true`) or isn't (`false`) null.", obj("type" to str("boolean")))
        }
    }

    private fun queryParameters(resource: Resource, paging: Boolean): List<JsonElement> {
        val keys = filterable(resource).joinToString("|") { it.name }
        return filterParameters(resource) + listOfNotNull(
            queryParameter(
                "sort", "The fields to sort by, in turn, such as `-${filterable(resource).last().name},id`: descending when prefixed with `-`.",
                obj("type" to str("string"), "pattern" to str("^-?($keys)(,-?($keys))*$")),
            ),
            obj("\$ref" to str("${PARAMETERS}basekt.limit")).takeIf { paging },
            obj("\$ref" to str("${PARAMETERS}basekt.cursor")).takeIf { paging },
        )
    }

    private fun aggregateParameters(resource: Resource): List<JsonElement> = filterParameters(resource) + listOf(
        queryParameter("groupBy", "The fields to group by, comma-separated. An instant field may name a bucket: `placedAt:month` (`day`, `week`, `month` or `year`).", obj("type" to str("string"))),
        queryParameter("timeZone", "The time zone region the buckets follow, such as `Europe/Paris`. UTC by default.", obj("type" to str("string"))),
        queryParameter("count", "`*` counts records, a field counts the records that have it set: `*,field` asks for both.", obj("type" to str("string"))),
        queryParameter("sum", "The fields to sum, comma-separated, each a measure named `sum_<field>`.", obj("type" to str("string"))),
        queryParameter("avg", "The fields to average, comma-separated, each a measure named `avg_<field>`.", obj("type" to str("string"))),
        queryParameter("min", "The fields to take the least of, comma-separated, each a measure named `min_<field>`.", obj("type" to str("string"))),
        queryParameter("max", "The fields to take the greatest of, comma-separated, each a measure named `max_<field>`.", obj("type" to str("string"))),
        queryParameter("sort", "Measures and group fields to sort by, such as `-count,kind`: descending when prefixed with `-`.", obj("type" to str("string"))),
        queryParameter("limit", "The most groups to return.", obj("type" to str("integer"), "minimum" to JsonPrimitive(1), "maximum" to JsonPrimitive(options.maxAggregateGroups), "default" to JsonPrimitive(options.maxAggregateGroups))),
    )

    private fun queryParameter(name: String, description: String, schema: JsonObject, explode: Boolean = true): JsonObject = obj(
        "name" to str(name),
        "in" to str("query"),
        "description" to str(description),
        "schema" to schema,
        "style" to str("form").takeIf { !explode },
        "explode" to JsonPrimitive(false).takeIf { !explode },
    )

    private fun parameterSchema(field: Field): JsonObject = when (field.type) {
        FieldType.STRING, FieldType.REFERENCE, FieldType.FILE -> obj("type" to str("string"))
        FieldType.ENUM -> obj("type" to str("string"), "enum" to strings(field.values.orEmpty()))
        FieldType.INT -> obj("type" to str("integer"), "format" to str("int32"))
        FieldType.LONG -> obj("type" to str("integer"), "format" to str("int64"))
        FieldType.DECIMAL -> obj("type" to str("string"), "pattern" to str(DECIMAL_PATTERN))
        FieldType.BOOL -> obj("type" to str("boolean"))
        FieldType.INSTANT -> obj("type" to str("string"), "format" to str("date-time"))
    }

    // BaseKt's own types

    private fun baseKtSchemas(): Map<String, JsonElement> = linkedMapOf(
        "basekt.ApiError" to obj(
            "type" to str("object"),
            "description" to str("The body of every error."),
            "required" to strings(listOf("error", "message", "fields", "requestId")),
            "properties" to obj(
                "error" to ref("basekt.ErrorCode"),
                "message" to obj("type" to str("string"), "description" to str("What went wrong, in a sentence.")),
                "fields" to obj("type" to str("array"), "items" to ref("basekt.FieldError"), "description" to str("The rejected fields, for `validation_failed`.")),
                "requestId" to obj("type" to strings(listOf("string", "null")), "description" to str("The id of the call, which the server's logs name too.")),
            ),
        ),
        "basekt.ErrorCode" to obj("type" to str("string"), "enum" to strings(serializer<ErrorCode>().descriptor.elementNames.toList())),
        "basekt.FieldError" to obj(
            "type" to str("object"),
            "required" to strings(listOf("field", "code", "message")),
            "properties" to obj(
                "field" to obj("type" to str("string")),
                "code" to ref("basekt.FieldErrorCode"),
                "message" to obj("type" to str("string")),
            ),
        ),
        "basekt.FieldErrorCode" to obj("type" to str("string"), "enum" to strings(serializer<FieldErrorCode>().descriptor.elementNames.toList())),
        "basekt.AggregatePage" to obj(
            "type" to str("object"),
            "required" to strings(listOf("groups", "hasMore")),
            "properties" to obj(
                "groups" to obj(
                    "type" to str("array"),
                    "items" to obj(
                        "type" to str("object"),
                        "required" to strings(listOf("key", "values")),
                        "properties" to obj(
                            "key" to obj("type" to str("object"), "description" to str("The group's value of each `groupBy` field.")),
                            "values" to obj("type" to str("object"), "description" to str("Each measure's value, such as `count` or `sum_quantity`.")),
                        ),
                    ),
                ),
                "hasMore" to obj("type" to str("boolean"), "description" to str("Whether `limit` cut groups off.")),
            ),
        ),
    )

    private fun securityScheme(security: OpenApiSecurity): JsonObject = when (security) {
        is OpenApiSecurity.Bearer -> obj("type" to str("http"), "scheme" to str("bearer"), "bearerFormat" to security.format?.let(::str))
        is OpenApiSecurity.ApiKey -> obj("type" to str("apiKey"), "in" to str("header"), "name" to str(security.header))
    }
}

/** A JSON object of the pairs whose value isn't `null`, in order. */
internal fun obj(vararg pairs: Pair<String, JsonElement?>): JsonObject {
    val map = linkedMapOf<String, JsonElement>()
    for ((key, value) in pairs) if (value != null) map[key] = value
    return JsonObject(map)
}

private fun str(value: String): JsonPrimitive = JsonPrimitive(value)

private fun strings(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))

private fun ref(name: String): JsonObject = obj("\$ref" to str(SCHEMAS + name))

/** A rule's decimal literal as a number. */
private fun number(literal: String): BigDecimal = BigDecimal(literal)

/** Escapes [text] for a literal match in an ECMAScript regular expression. */
private fun jsEscape(text: String): String = text.replace(Regex("""[.*+?^${'$'}{}()|\[\]\\/]"""),"""\\$0""")
