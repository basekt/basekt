package io.github.basekt.server

import io.github.basekt.data.DataSource
import io.github.basekt.schema.FieldValidator
import io.github.basekt.schema.Model
import io.github.basekt.schema.Schema
import io.github.basekt.schema.SchemaBuilder
import io.github.basekt.schema.missingValidators
import io.ktor.events.EventDefinition
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.log
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlin.time.Clock

/**
 * The configuration of [BaseKt]. Settings can come from the `basekt` section of the application's configuration:
 *
 * ```yaml
 * basekt:
 *   basePath: /api
 *   pageSize:
 *     default: 20
 *     max: 100
 *   requestIdHeader: X-Request-Id
 * ```
 *
 * Values set in the `install` block win over the configuration file.
 */
public class BaseKtConfig internal constructor(config: ApplicationConfig) {
    /** Where the records live. Required. */
    public var dataSource: DataSource? = null

    /** The schema to serve. Set it directly, or declare it with [resources]. Required. */
    public var schema: Schema? = null

    /** The path the routes are mounted under. `basekt.basePath`, `/api` by default. */
    public var basePath: String = config.propertyOrNull("basePath")?.getString() ?: "/api"

    /** The page size when a list request gives no `limit`. `basekt.pageSize.default`, 20 by default. */
    public var defaultPageSize: Int = config.propertyOrNull("pageSize.default")?.getString()?.toInt() ?: 20

    /** The largest `limit` a list request may ask for. `basekt.pageSize.max`, 100 by default. */
    public var maxPageSize: Int = config.propertyOrNull("pageSize.max")?.getString()?.toInt() ?: 100

    /** The most groups an aggregate request may return, and the default. `basekt.aggregate.maxLimit`, 1000 by default. */
    public var maxAggregateGroups: Int = config.propertyOrNull("aggregate.maxLimit")?.getString()?.toInt() ?: 1000

    /**
     * The header that carries each call's [requestId], in requests from a proxy and in every response.
     * `basekt.requestIdHeader`, `X-Request-Id` by default. A browser on another origin reads it only if CORS exposes it.
     */
    public var requestIdHeader: String = config.propertyOrNull("requestIdHeader")?.getString() ?: "X-Request-Id"

    /** Finds out who made each call. Anonymous by default; an auth module replaces it. */
    public var identities: IdentityResolver = IdentityResolver.Anonymous

    /** The validators that the schema's custom rules name. */
    public val validators: MutableMap<String, FieldValidator> = mutableMapOf()

    /** Where the times of `createdAt()` and `updatedAt()` fields come from. The system clock by default. */
    public var clock: Clock = Clock.System

    private val hookBuilder = HooksBuilder()

    /** Declares hooks that run around writes, on every transport. See [Hooks]. */
    public fun hooks(block: HooksBuilder.() -> Unit) {
        hookBuilder.apply(block)
    }

    internal fun builtHooks(): Hooks = hookBuilder.build(models.toList())

    private val actionBuilder = ActionsBuilder()

    /** Registers the code of the schema's actions. Every declared action needs code, and code needs an action. */
    public fun actions(block: ActionsBuilder.() -> Unit) {
        actionBuilder.apply(block)
    }

    internal fun builtActions(): Actions = actionBuilder.build()

    /**
     * The typed resources of the schema, which repositories and typed hooks find by class. [resources] fills it;
     * add models here yourself when you set [schema] directly.
     */
    public val models: MutableList<Model<*>> = mutableListOf()

    /** Declares the schema with the schema DSL, typed resources included. */
    public fun resources(block: SchemaBuilder.() -> Unit) {
        val builder = SchemaBuilder().apply(block)
        schema = builder.build()
        models += builder.models
    }

    /** Registers [validator] for custom rules named [name]. */
    public fun validator(name: String, validator: FieldValidator) {
        validators[name] = validator
    }
}

/** What an installed [BaseKt] serves, for routes and other modules to build on. */
public class BaseKtRuntime internal constructor(
    /** The schema being served. */
    public val schema: Schema,
    /** Where the records live. */
    public val dataSource: DataSource,
    /** The operations behind the routes. */
    public val service: ResourceService,
    /** Finds out who made each call. */
    public val identities: IdentityResolver,
    /** The path the routes are mounted under. */
    public val basePath: String,
    /** The typed resources, which [repository] finds by class. */
    public val models: List<Model<*>>,
    /** Where times come from, such as the automatic `createdAt()` fields'. Modules use it for their own times. */
    public val clock: Clock = Clock.System,
    /** The most groups an aggregate request may return. */
    public val maxAggregateGroups: Int = 1000,
)

private val RuntimeKey = AttributeKey<BaseKtRuntime>("BaseKtRuntime")

/** The installed [BaseKt]'s runtime. Throws [IllegalStateException] if [BaseKt] isn't installed. */
public val Application.baseKt: BaseKtRuntime
    get() = attributes.getOrNull(RuntimeKey) ?: error("BaseKt is not installed in this application.")

/** Raised once the application has started and BaseKt is serving its routes. */
public val BaseKtStarted: EventDefinition<BaseKtRuntime> = EventDefinition()

/**
 * Serves a schema's resources as a REST API under [BaseKtConfig.basePath]:
 * - `GET /schema`: the schema as JSON;
 * - `GET /{resource}`: a page of records (`items`, `nextCursor`, `hasNext`), filtered and sorted by query parameters (see [parseQuery]);
 * - `POST /{resource}`: create a record; `GET`, `PATCH` and `DELETE /{resource}/{id}`: one record;
 * - `GET /{resource}/{id}/{relation}`: the record it belongs to, or the records that belong to it.
 *
 * Every operation checks the resource's permissions against the caller's roles from
 * [BaseKtConfig.identities]. Errors are [ApiError] JSON bodies. Every call of the application gets a [requestId], sent
 * back in [BaseKtConfig.requestIdHeader] and in error bodies, and server errors are logged with it.
 */
public val BaseKt: io.ktor.server.application.ApplicationPlugin<BaseKtConfig> = createApplicationPlugin(
    name = "BaseKt",
    configurationPath = "basekt",
    createConfiguration = ::BaseKtConfig,
) {
    val dataSource = requireNotNull(pluginConfig.dataSource) { "BaseKt needs a dataSource." }
    val schema = requireNotNull(pluginConfig.schema) { "BaseKt needs a schema: set schema or declare resources { }." }
        .requireValid()
    val validators = pluginConfig.validators.toMap()
    val missing = schema.missingValidators(validators)
    require(missing.isEmpty()) { "The schema's custom rules name validators that aren't registered: ${missing.joinToString()}." }
    require(schema.resource(SCHEMA_PATH) == null) { "A resource can't be called '$SCHEMA_PATH': GET $SCHEMA_PATH serves the schema." }
    require(schema.resource(AGGREGATE_PATH) == null) { "A resource can't be called '$AGGREGATE_PATH': GET $AGGREGATE_PATH/{resource} serves aggregates." }
    require(pluginConfig.maxAggregateGroups >= 1) { "maxAggregateGroups must be at least 1." }
    val defaultPageSize = pluginConfig.defaultPageSize
    val maxPageSize = pluginConfig.maxPageSize
    require(maxPageSize >= 1 && defaultPageSize in 1..maxPageSize) {
        "Page sizes must satisfy 1 <= default ($defaultPageSize) <= max ($maxPageSize)."
    }
    val basePath = "/" + pluginConfig.basePath.trim('/')
    val requestIdHeader = pluginConfig.requestIdHeader
    require(requestIdHeader.isNotBlank()) { "requestIdHeader can't be blank." }

    val service = ResourceService(schema, dataSource, validators, pluginConfig.builtHooks(), pluginConfig.clock, pluginConfig.builtActions())
    val runtime = BaseKtRuntime(schema, dataSource, service, pluginConfig.identities, basePath, pluginConfig.models.toList(), pluginConfig.clock, pluginConfig.maxAggregateGroups)
    application.attributes.put(RuntimeKey, runtime)
    application.routing { withIdentities(runtime.identities) { baseKtRoutes(runtime, defaultPageSize, maxPageSize) } }

    onCall { call -> call.assignRequestId(requestIdHeader) }

    on(MonitoringEvent(ApplicationStarted)) { app ->
        app.log.info("BaseKt serves ${schema.resources.size} resources under $basePath")
        app.monitor.raise(BaseKtStarted, runtime)
    }
    on(MonitoringEvent(ApplicationStopping)) { app ->
        app.log.info("BaseKt stops serving $basePath")
    }
}

internal const val SCHEMA_PATH = "schema"

internal const val AGGREGATE_PATH = "aggregate"
