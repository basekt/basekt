package io.github.basekt.admin

import io.github.basekt.data.Counting
import io.github.basekt.data.Filter
import io.github.basekt.data.InvalidQueryException
import io.github.basekt.data.Page
import io.github.basekt.data.Query
import io.github.basekt.schema.Field
import io.github.basekt.schema.FieldType
import io.github.basekt.schema.Operation
import io.github.basekt.schema.Relation
import io.github.basekt.schema.Resource
import io.github.basekt.schema.encodeRecord
import io.github.basekt.schema.toJson
import io.github.basekt.server.ApiError
import io.github.basekt.server.ApiException
import io.github.basekt.server.BaseKtRuntime
import io.github.basekt.server.ErrorCode
import io.github.basekt.server.Identity
import io.github.basekt.server.parseAggregateQuery
import io.github.basekt.server.parseQuery
import io.github.basekt.server.toJson
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.util.toMap
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

internal fun Route.adminRoutes(runtime: BaseKtRuntime, settings: AdminSettings) {
    val service = runtime.service
    route(settings.path) {
        get("schema") {
            handle(runtime) { caller -> respondJson(AdminSchema.serializer(), adminSchema(runtime, caller)) }
        }
        route("resources/{resource}") {
            route("records") {
                get {
                    handle(runtime) { caller ->
                        val resource = resource(runtime)
                        val query = query(resource, settings.defaultPageSize, settings.maxPageSize)
                        val page = service.list(resource, caller, query)
                        val scope = service.scopeFilter(resource, Operation.READ, caller)
                        val counted = listOfNotNull(query.filter, scope).let { if (it.size > 1) Filter.And(it) else it.firstOrNull() }
                        respondJson(AdminPage.serializer(), page.toAdmin(resource, count(runtime, resource, counted)))
                    }
                }
                post {
                    handle(runtime) { caller ->
                        val resource = resource(runtime)
                        respondRecord(resource, service.create(resource, caller, body()), HttpStatusCode.Created)
                    }
                }
                route("{id}") {
                    get {
                        handle(runtime) { caller ->
                            val resource = resource(runtime)
                            respondRecord(resource, service.get(resource, caller, id()))
                        }
                    }
                    patch {
                        handle(runtime) { caller ->
                            val resource = resource(runtime)
                            respondRecord(resource, service.update(resource, caller, id(), body()))
                        }
                    }
                    delete {
                        handle(runtime) { caller ->
                            service.delete(resource(runtime), caller, id())
                            call.respond(HttpStatusCode.NoContent)
                        }
                    }
                    post("actions/{action}") {
                        handle(runtime) { caller ->
                            respondAnswer(service.run(resource(runtime), call.parameters["action"]!!, caller, id(), optionalBody()))
                        }
                    }
                }
            }
            post("actions/{action}") {
                handle(runtime) { caller ->
                    respondAnswer(service.run(resource(runtime), call.parameters["action"]!!, caller, null, optionalBody()))
                }
            }
            post("bulk-delete") {
                handle(runtime) { caller ->
                    val resource = resource(runtime)
                    val ids = body(BulkDeleteRequest.serializer()).ids.distinct()
                    if (ids.isEmpty() || ids.size > settings.maxBulkDelete) {
                        throw ApiException(ErrorCode.BAD_REQUEST, "A bulk delete names from 1 to ${settings.maxBulkDelete} ids.")
                    }
                    val deleted = mutableListOf<String>()
                    val failed = mutableListOf<BulkFailure>()
                    for (id in ids) {
                        try {
                            service.delete(resource, caller, id)
                            deleted += id
                        } catch (e: ApiException) {
                            // Permissions are per resource: if the first id is refused, every id would be.
                            if (e.error.error == ErrorCode.UNAUTHORIZED || e.error.error == ErrorCode.FORBIDDEN) throw e
                            failed += BulkFailure(id, e.error)
                        }
                    }
                    respondJson(BulkDeleteResult.serializer(), BulkDeleteResult(deleted, failed))
                }
            }
            get("aggregate") {
                handle(runtime) { caller ->
                    val resource = resource(runtime)
                    val parameters = call.request.queryParameters.toMap()
                    var query = parseAggregateQuery(resource, parameters - SEARCH, runtime.maxAggregateGroups)
                    search(resource, parameters)?.let { search -> query = query.copy(filter = query.filter?.let { Filter.And(listOf(it, search)) } ?: search) }
                    val page = service.aggregate(resource, caller, query)
                    val groups = page.groups.map { group ->
                        AdminAggregateGroup(
                            JsonObject(group.key.mapValues { (_, value) -> value?.toJson() ?: JsonNull }),
                            JsonObject(group.values.mapValues { (_, value) -> value?.toJson() ?: JsonNull }),
                        )
                    }
                    respondJson(AdminAggregatePage.serializer(), AdminAggregatePage(groups, page.hasMore))
                }
            }
            get("export.csv") {
                handle(runtime) { caller -> exportCsv(runtime, settings, caller) }
            }
        }
    }
}

/** Builds the [AdminSchema] that [caller] may see. */
internal fun adminSchema(runtime: BaseKtRuntime, caller: Identity?): AdminSchema {
    val roles = caller?.roles
    val readable = runtime.schema.resources.filter { it.permissions.read.allows(roles) }.map { it.name }.toSet()
    val resources = runtime.schema.resources.mapNotNull { resource ->
        val operations = Operation.entries.filter { resource.permissions.of(it).allows(roles) }
        val actions = resource.runnableActions.filter { it.access.allows(roles) }
        if (operations.isEmpty() && actions.isEmpty()) return@mapNotNull null
        val scoped = runtime.service.scopedValues(resource, Operation.CREATE, caller)
        AdminResource(
            name = resource.name,
            fields = resource.visibleFields().map { it.toAdmin(scoped[it.name]) },
            relations = resource.relations.filter { it.target in readable }.map { relation ->
                when (relation) {
                    is Relation.BelongsTo -> AdminRelation(relation.name, RelationKind.BELONGS_TO, relation.target, relation.field)
                    is Relation.HasMany -> AdminRelation(relation.name, RelationKind.HAS_MANY, relation.target, relation.foreignField)
                }
            },
            operations = operations,
            searchable = resource.searchable(),
            sortable = listOf(Resource.ID) + resource.visibleFields().map { it.name },
            group = resource.group,
            actions = actions.map { action ->
                val transition = resource.transition(action.name)
                AdminAction(
                    action.name, action.target, action.input.map { it.toAdmin(null) }, action.confirm,
                    stateField = transition?.let { resource.workflow!!.field }, from = transition?.from,
                )
            },
        )
    }
    return AdminSchema(caller = caller?.let { AdminCaller(it.id, it.roles.sorted()) }, resources = resources)
}

private fun Field.toAdmin(scopedValues: Set<String>?): AdminField =
    AdminField(name, type, required, values, target, rules, bucket, scopedValues?.sorted(), auto, list)

/** The fields a panel may show: the ones that aren't [Field.hidden]. */
private fun Resource.visibleFields(): List<Field> = fields.filterNot { it.hidden }

private fun Resource.searchable(): List<String> =
    visibleFields().filter { it.type == FieldType.STRING || it.type == FieldType.ENUM }.map { it.name }

/** Parses the list parameters: BaseKt's filters, sort, limit and cursor, plus `search` over the searchable fields. */
private fun RoutingContext.query(resource: Resource, defaultLimit: Int, maxLimit: Int): Query {
    val parameters = call.request.queryParameters.toMap()
    val query = parseQuery(resource, parameters - SEARCH, defaultLimit, maxLimit)
    val search = search(resource, parameters) ?: return query
    return query.copy(filter = query.filter?.let { Filter.And(listOf(it, search)) } ?: search)
}

private suspend fun count(runtime: BaseKtRuntime, resource: Resource, filter: Filter?): Long? {
    val counting = runtime.dataSource as? Counting ?: return null
    return try {
        counting.count(resource, filter)
    } catch (e: InvalidQueryException) {
        throw ApiException(ErrorCode.INVALID_QUERY, e.message!!)
    }
}

private suspend fun RoutingContext.exportCsv(runtime: BaseKtRuntime, settings: AdminSettings, caller: Identity?) {
    val resource = resource(runtime)
    val first = query(resource, settings.maxPageSize, settings.maxPageSize).copy(limit = settings.maxPageSize, cursor = null)
    // Read the first page before answering, so a refused or invalid export is an error, not a broken file.
    val firstPage = runtime.service.list(resource, caller, first)
    val total = count(runtime, resource, first.filter)
    if (total != null && total > settings.maxExportRows) {
        throw ApiException(
            ErrorCode.INVALID_QUERY,
            "The export would have $total rows, more than the limit of ${settings.maxExportRows}. Narrow it with filters.",
        )
    }
    call.response.header(
        HttpHeaders.ContentDisposition,
        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "${resource.name}.csv").toString(),
    )
    call.respondTextWriter(ContentType.Text.CSV.withParameter("charset", "utf-8")) {
        val csv = CsvWriter(this, resource)
        csv.header()
        var page = firstPage
        var rows = 0
        while (true) {
            for (record in page.items) {
                if (rows == settings.maxExportRows) return@respondTextWriter
                csv.row(record)
                rows++
            }
            val cursor = page.nextCursor ?: break
            page = runtime.service.list(resource, caller, first.copy(cursor = cursor))
        }
    }
}

private fun Page.toAdmin(resource: Resource, total: Long?): AdminPage =
    AdminPage(items.map(resource::encodeRecord), nextCursor, hasNext, total)

/** Resolves the caller, runs [block], and answers any [ApiException] with its [ApiError]. */
private suspend fun RoutingContext.handle(runtime: BaseKtRuntime, block: suspend RoutingContext.(Identity?) -> Unit) {
    try {
        block(runtime.identities.resolve(call))
    } catch (e: ApiException) {
        call.respondText(e.error.toJson(), ContentType.Application.Json, HttpStatusCode.fromValue(e.error.error.status))
    }
}

private fun RoutingContext.resource(runtime: BaseKtRuntime): Resource = runtime.service.resource(call.parameters["resource"]!!)

private fun RoutingContext.id(): String = call.parameters["id"]!!

/** Reads the body as a JSON object, or an empty one when there is no body. */
private suspend fun RoutingContext.optionalBody(): JsonObject {
    val text = call.receiveText()
    if (text.isBlank()) return JsonObject(emptyMap())
    return runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        ?: throw ApiException(ErrorCode.BAD_REQUEST, "The request body must be a JSON object.")
}

/** Answers with an action's [answer], or no content. */
private suspend fun RoutingContext.respondAnswer(answer: JsonObject?) {
    if (answer == null) call.respond(HttpStatusCode.NoContent) else call.respondText(answer.toString(), ContentType.Application.Json)
}

private suspend fun RoutingContext.body(): JsonObject {
    val text = call.receiveText()
    return runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        ?: throw ApiException(ErrorCode.BAD_REQUEST, "The request body must be a JSON object.")
}

private suspend fun <T> RoutingContext.body(serializer: KSerializer<T>): T = try {
    AdminJson.decodeFromString(serializer, call.receiveText())
} catch (e: SerializationException) {
    throw ApiException(ErrorCode.BAD_REQUEST, "The request body doesn't match the expected JSON: ${e.message}")
} catch (e: IllegalArgumentException) {
    throw ApiException(ErrorCode.BAD_REQUEST, "The request body doesn't match the expected JSON: ${e.message}")
}

private suspend fun RoutingContext.respondRecord(resource: Resource, record: io.github.basekt.schema.Record, status: HttpStatusCode = HttpStatusCode.OK) {
    call.respondText(resource.encodeRecord(record).toString(), ContentType.Application.Json, status)
}

private suspend fun <T> RoutingContext.respondJson(serializer: KSerializer<T>, value: T) {
    call.respondText(AdminJson.encodeToString(serializer, value), ContentType.Application.Json)
}

private const val SEARCH = "search"

/** The condition the `search` parameter makes over the searchable fields, or `null` without one. */
private fun search(resource: Resource, parameters: Map<String, List<String>>): Filter? {
    val text = parameters[SEARCH]?.lastOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val fields = resource.searchable()
    if (fields.isEmpty()) throw ApiException(ErrorCode.INVALID_QUERY, "'${resource.name}' has no text fields to search.")
    return Filter.Or(fields.map { Filter.Contains(it, text) })
}
