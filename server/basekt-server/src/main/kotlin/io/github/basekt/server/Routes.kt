package io.github.basekt.server

import io.github.basekt.data.Page
import io.github.basekt.schema.Record
import io.github.basekt.schema.Relation
import io.github.basekt.schema.Resource
import io.github.basekt.schema.encodeRecord
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.util.toMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val ErrorJson = Json { encodeDefaults = true }

internal fun Route.baseKtRoutes(runtime: BaseKtRuntime, defaultPageSize: Int, maxPageSize: Int) {
    val service = runtime.service
    val schemaJson = runtime.schema.toJson()
    fun RoutingCall.query(resource: Resource) =
        parseQuery(resource, request.queryParameters.toMap(), defaultPageSize, maxPageSize)

    route(runtime.basePath) {
        get(SCHEMA_PATH) { call.respondText(schemaJson, ContentType.Application.Json) }

        for (resource in runtime.schema.resources) {
            route(resource.name) {
                get {
                    handle(runtime) { caller -> respondPage(resource, service.list(resource, caller, call.query(resource))) }
                }
                post {
                    handle(runtime) { caller ->
                        val created = service.create(resource, caller, body())
                        call.response.header(HttpHeaders.Location, "${runtime.basePath}/${resource.name}/${created.idOf()}")
                        respondJson(resource.encodeRecord(created), HttpStatusCode.Created)
                    }
                }
                route("{id}") {
                    get {
                        handle(runtime) { caller -> respondJson(resource.encodeRecord(service.get(resource, caller, id()))) }
                    }
                    patch {
                        handle(runtime) { caller ->
                            respondJson(resource.encodeRecord(service.update(resource, caller, id(), body())))
                        }
                    }
                    delete {
                        handle(runtime) { caller ->
                            service.delete(resource, caller, id())
                            call.respond(HttpStatusCode.NoContent)
                        }
                    }
                    for (relation in resource.relations) {
                        val target = runtime.schema.resource(relation.target)!!
                        get(relation.name) {
                            handle(runtime) { caller ->
                                when (relation) {
                                    is Relation.BelongsTo ->
                                        respondJson(target.encodeRecord(service.parent(resource, caller, id(), relation)))
                                    is Relation.HasMany ->
                                        respondPage(target, service.children(resource, caller, id(), relation, call.query(target)))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Resolves the caller, runs [block], and answers any [ApiException] with its [ApiError]. */
private suspend fun RoutingContext.handle(runtime: BaseKtRuntime, block: suspend RoutingContext.(Identity?) -> Unit) {
    try {
        block(runtime.identities.resolve(call))
    } catch (e: ApiException) {
        call.respondText(
            ErrorJson.encodeToString(ApiError.serializer(), e.error),
            ContentType.Application.Json,
            HttpStatusCode.fromValue(e.error.error.status),
        )
    }
}

private fun RoutingContext.id(): String = call.parameters["id"]!!

private suspend fun RoutingContext.body(): JsonObject {
    val text = call.receiveText()
    return runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        ?: throw ApiException(ErrorCode.BAD_REQUEST, "The request body must be a JSON object.")
}

private suspend fun RoutingContext.respondJson(json: JsonElement, status: HttpStatusCode = HttpStatusCode.OK) {
    call.respondText(json.toString(), ContentType.Application.Json, status)
}

private suspend fun RoutingContext.respondPage(resource: Resource, page: Page) {
    respondJson(
        JsonObject(
            mapOf(
                "items" to JsonArray(page.items.map(resource::encodeRecord)),
                "nextCursor" to (page.nextCursor?.let(::JsonPrimitive) ?: JsonNull),
            ),
        ),
    )
}

private fun Record.idOf(): String = this[Resource.ID].toString()
