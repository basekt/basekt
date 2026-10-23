package io.github.basekt.audit

import io.github.basekt.server.BaseKt
import io.github.basekt.server.baseKt
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.log
import io.ktor.server.application.pluginOrNull
import io.ktor.server.config.ApplicationConfig

/**
 * The configuration of [BaseKtAudit]. The log's resource name can come from `basekt.audit.resource`; what to redact
 * and skip is set in code.
 */
public class BaseKtAuditConfig internal constructor(config: ApplicationConfig) {
    /** The name of the audit log resource. `basekt.audit.resource`, `audit` by default. */
    public var resource: String = config.propertyOrNull("resource")?.getString() ?: "audit"

    internal val redacted = linkedSetOf<String>()
    internal val skipped = linkedSetOf<String>()

    /**
     * Logs the values of [fields], each written `resource.field`, as `[redacted]`: what changed shows, not the value.
     * An action's input field is written `resource.inputField`.
     */
    public fun redact(vararg fields: String) {
        for (field in fields) {
            require(field.count { it == '.' } == 1) { "'$field' isn't a field to redact: write it as resource.field." }
            redacted += field
        }
    }

    /** Leaves [resources] out of the log. */
    public fun skip(vararg resources: String) {
        skipped += resources
    }
}

/**
 * Logs every create, update, delete and action that goes through BaseKt's service, from every transport, in the
 * [BaseKtAuditConfig.resource] resource: when, who, what, on which record, and what changed. Each entry is written in
 * the same transaction as the write it logs, so a write that rolls back leaves no entry, and one that commits always
 * has one. An action's own entry and its writes' entries share an `operationId`.
 *
 * Declare the log in the schema with [auditLog], and install [BaseKt] first. Writes that trusted code makes through
 * raw data access, such as a hook's `data` or a `Repository`, aren't logged.
 */
public val BaseKtAudit: ApplicationPlugin<BaseKtAuditConfig> = createApplicationPlugin(
    name = "BaseKtAudit",
    configurationPath = "basekt.audit",
    createConfiguration = ::BaseKtAuditConfig,
) {
    checkNotNull(application.pluginOrNull(BaseKt)) { "Install BaseKt before BaseKtAudit." }
    val baseKt = application.baseKt
    val name = pluginConfig.resource
    val log = requireNotNull(baseKt.schema.resource(name)) {
        "The schema has no '$name' resource for the audit log. Declare it with auditLog(\"$name\") in resources { }."
    }
    val problems = auditLogProblems(log)
    require(problems.isEmpty()) { "'$name' can't be the audit log: ${problems.joinToString("; ")}." }
    for (field in pluginConfig.redacted) {
        val (resource, fieldName) = field.split('.')
        val target = requireNotNull(baseKt.schema.resource(resource)) { "redact names the resource '$resource', which the schema doesn't have." }
        require(target.field(fieldName) != null || target.actions.any { action -> action.input.any { it.name == fieldName } }) {
            "redact names '$field', which is neither a field of '$resource' nor an input of its actions."
        }
    }
    val unknown = pluginConfig.skipped.filter { baseKt.schema.resource(it) == null }
    require(unknown.isEmpty()) { "skip names resources the schema doesn't have: ${unknown.joinToString()}." }

    baseKt.service.extend(AuditRecorder(log, baseKt.clock, pluginConfig.redacted.toSet(), pluginConfig.skipped.toSet()))

    on(MonitoringEvent(ApplicationStarted)) { app ->
        app.log.info("BaseKt audit logs every write in '$name'")
    }
}
