package io.github.basekt.openapi

import io.github.basekt.server.BaseKt
import io.github.basekt.server.baseKt
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.log
import io.ktor.server.application.pluginOrNull
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The configuration of [BaseKtOpenApi]. Settings can come from the `basekt.openapi` section of the application's
 * configuration; values set in the `install` block win.
 *
 * ```yaml
 * basekt:
 *   openapi:
 *     path: /openapi.json
 *     title: Example API
 *     version: 2.1.0
 *     servers: [https://api.example.com]
 * ```
 */
public class BaseKtOpenApiConfig internal constructor(config: ApplicationConfig) {
    /** Where the document is served. `basekt.openapi.path`, `/openapi.json` by default. */
    public var path: String = config.propertyOrNull("path")?.getString() ?: "/openapi.json"

    /** The API's name. `basekt.openapi.title`, `BaseKt API` by default. */
    public var title: String = config.propertyOrNull("title")?.getString() ?: "BaseKt API"

    /** The API's own version. `basekt.openapi.version`, `1.0.0` by default. */
    public var version: String = config.propertyOrNull("version")?.getString() ?: "1.0.0"

    /** A longer description, in Markdown. `basekt.openapi.description`, none by default. */
    public var description: String? = config.propertyOrNull("description")?.getString()

    /** The URLs the API is served at. `basekt.openapi.servers`, none by default. */
    public val servers: MutableList<String> = config.propertyOrNull("servers")?.getList().orEmpty().toMutableList()

    /** How callers authenticate, or `null` to leave security out. Set it to match the auth module installed. */
    public var security: OpenApiSecurity? = null

    internal val merged = mutableListOf<JsonObject>()

    /** Adds [document]'s routes and components, such as the application's own routes. See [mergeOpenApi]. */
    public fun merge(document: JsonObject) {
        merged += document
    }

    /** Adds the routes and components of the OpenAPI document in [json]. See [mergeOpenApi]. */
    public fun merge(json: String) {
        merge(Json.parseToJsonElement(json) as? JsonObject ?: throw IllegalArgumentException("A merged OpenAPI document must be a JSON object."))
    }
}

/**
 * Serves an OpenAPI 3.1 document of the REST API [BaseKt] serves, at [BaseKtOpenApiConfig.path], built once at install
 * by [toOpenApi] from the schema and BaseKt's settings, with the documents given to [BaseKtOpenApiConfig.merge] merged
 * in. The document is the same for every caller and is open to everyone. Install [BaseKt] first.
 */
public val BaseKtOpenApi: ApplicationPlugin<BaseKtOpenApiConfig> = createApplicationPlugin(
    name = "BaseKtOpenApi",
    configurationPath = "basekt.openapi",
    createConfiguration = ::BaseKtOpenApiConfig,
) {
    checkNotNull(application.pluginOrNull(BaseKt)) { "Install BaseKt before BaseKtOpenApi." }
    val runtime = application.baseKt
    val path = pluginConfig.path
    val reserved = runtime.schema.resources.map { it.name } + listOf("schema", "aggregate")
    val base = runtime.basePath.trimEnd('/')
    require(path.startsWith('/') && path.length > 1) { "BaseKtOpenApi's path '$path' must start with '/' and name a route." }
    val clashes = path == runtime.basePath || path.startsWith("$base/") && path.removePrefix("$base/").substringBefore('/') in reserved
    require(!clashes) {
        "BaseKtOpenApi's path '$path' is one of BaseKt's routes under '${runtime.basePath}'."
    }
    val options = OpenApiOptions(
        title = pluginConfig.title,
        version = pluginConfig.version,
        description = pluginConfig.description,
        servers = pluginConfig.servers.toList(),
        basePath = runtime.basePath,
        defaultPageSize = runtime.defaultPageSize,
        maxPageSize = runtime.maxPageSize,
        maxAggregateGroups = runtime.maxAggregateGroups,
        requestIdHeader = runtime.requestIdHeader,
        security = pluginConfig.security,
    )
    val problems = options.problems()
    require(problems.isEmpty()) { "BaseKtOpenApi's settings are not valid:\n" + problems.joinToString("\n") { "- $it" } }
    val document = pluginConfig.merged.fold(runtime.schema.toOpenApi(options)) { document, other -> document.mergeOpenApi(other) }
    val text = document.toOpenApiJson()

    application.routing { get(path) { call.respondText(text, ContentType.Application.Json) } }

    on(MonitoringEvent(ApplicationStarted)) { app ->
        app.log.info("BaseKt OpenAPI document of ${runtime.schema.resources.size} resources served at $path")
    }
}
