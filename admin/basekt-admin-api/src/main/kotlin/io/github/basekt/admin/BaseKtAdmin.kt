package io.github.basekt.admin

import io.github.basekt.server.BaseKt
import io.github.basekt.server.baseKt
import io.github.basekt.server.withIdentities
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.log
import io.ktor.server.application.pluginOrNull
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.routing.routing

/**
 * The configuration of [BaseKtAdmin]. Settings can come from the `basekt.admin` section of the application's
 * configuration; values set in the `install` block win.
 */
public class BaseKtAdminConfig internal constructor(config: ApplicationConfig) {
    /** Where the admin API is mounted. `basekt.admin.path`, `/admin/api` by default. */
    public var path: String = config.propertyOrNull("path")?.getString() ?: "/admin/api"

    /** The page size when a list gives no `limit`. `basekt.admin.pageSize.default`, 25 by default. */
    public var defaultPageSize: Int = config.propertyOrNull("pageSize.default")?.getString()?.toInt() ?: 25

    /** The largest `limit` a list may ask for. `basekt.admin.pageSize.max`, 200 by default. */
    public var maxPageSize: Int = config.propertyOrNull("pageSize.max")?.getString()?.toInt() ?: 200

    /** The most ids one bulk delete may name. `basekt.admin.maxBulkDelete`, 500 by default. */
    public var maxBulkDelete: Int = config.propertyOrNull("maxBulkDelete")?.getString()?.toInt() ?: 500

    /** The most rows one CSV export may have. `basekt.admin.maxExportRows`, 100 000 by default. */
    public var maxExportRows: Int = config.propertyOrNull("maxExportRows")?.getString()?.toInt() ?: 100_000
}

/**
 * Serves a JSON admin API for the schema [BaseKt] serves, under [BaseKtAdminConfig.path]. It renders no HTML: an
 * admin panel builds itself from `GET {path}/schema` and calls the rest.
 *
 * | Route | What it does |
 * |---|---|
 * | `GET /schema` | the [AdminSchema] for the caller: the resources and operations their roles allow |
 * | `GET /resources/{name}/records` | a page ([AdminPage]): `field[op]=` filters, `sort`, `search`, `limit`, `cursor` |
 * | `POST /resources/{name}/records` | creates a record |
 * | `GET`, `PATCH`, `DELETE /resources/{name}/records/{id}` | reads, patches or deletes one record |
 * | `POST /resources/{name}/bulk-delete` | deletes several records ([BulkDeleteRequest] → [BulkDeleteResult]) |
 * | `GET /resources/{name}/export.csv` | the matching records as CSV, with the list's filters, sort and search |
 *
 * Every operation goes through BaseKt's `ResourceService`, so permissions, validation and hooks are the same as on
 * the REST API. Callers are resolved by BaseKt's identity resolver. Errors are `ApiError` JSON bodies. Install
 * [BaseKt] first.
 */
public val BaseKtAdmin: ApplicationPlugin<BaseKtAdminConfig> = createApplicationPlugin(
    name = "BaseKtAdmin",
    configurationPath = "basekt.admin",
    createConfiguration = ::BaseKtAdminConfig,
) {
    checkNotNull(application.pluginOrNull(BaseKt)) { "Install BaseKt before BaseKtAdmin." }
    val runtime = application.baseKt
    val linkProblems = runtime.schema.adminLinkProblems()
    require(linkProblems.isEmpty()) { "The schema's admin links are not valid:\n" + linkProblems.joinToString("\n") { "- $it" } }
    val viewProblems = runtime.schema.adminViewProblems()
    require(viewProblems.isEmpty()) { "The schema's admin views are not valid:\n" + viewProblems.joinToString("\n") { "- $it" } }
    val settings = AdminSettings(
        path = "/" + pluginConfig.path.trim('/'),
        defaultPageSize = pluginConfig.defaultPageSize,
        maxPageSize = pluginConfig.maxPageSize,
        maxBulkDelete = pluginConfig.maxBulkDelete,
        maxExportRows = pluginConfig.maxExportRows,
    )
    require(settings.path != "/" && settings.path != runtime.basePath) {
        "BaseKtAdmin's path '${settings.path}' must differ from '/' and from BaseKt's '${runtime.basePath}'."
    }
    require(settings.maxPageSize >= 1 && settings.defaultPageSize in 1..settings.maxPageSize) {
        "Admin page sizes must satisfy 1 <= default (${settings.defaultPageSize}) <= max (${settings.maxPageSize})."
    }
    require(settings.maxBulkDelete >= 1 && settings.maxExportRows >= 1) {
        "maxBulkDelete and maxExportRows must be at least 1."
    }

    application.routing { withIdentities(runtime.identities) { adminRoutes(runtime, settings) } }

    on(MonitoringEvent(ApplicationStarted)) { app ->
        app.log.info("BaseKt admin API serves ${runtime.schema.resources.size} resources under ${settings.path}")
    }
}

/** [BaseKtAdminConfig], checked and frozen at install. */
internal class AdminSettings(
    val path: String,
    val defaultPageSize: Int,
    val maxPageSize: Int,
    val maxBulkDelete: Int,
    val maxExportRows: Int,
)
