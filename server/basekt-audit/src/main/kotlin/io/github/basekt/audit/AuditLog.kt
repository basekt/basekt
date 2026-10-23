package io.github.basekt.audit

import io.github.basekt.schema.Access
import io.github.basekt.schema.FieldType
import io.github.basekt.schema.Resource
import io.github.basekt.schema.SchemaBuilder
import io.github.basekt.schema.schema

/** The fields of an audit log resource, as [auditLog] declares them. */
public object AuditFields {
    /** When it happened, an instant. */
    public const val AT: String = "at"

    /** The id of the caller, or null for an anonymous one. */
    public const val ACTOR: String = "actor"

    /** What happened: one of [OPERATIONS]. */
    public const val OPERATION: String = "operation"

    /** The name of the resource written. */
    public const val RESOURCE: String = "resource"

    /** The id of the record written, or of the record an action ran on. */
    public const val RECORD_ID: String = "recordId"

    /** The action this entry is, or is part of, or null for a write of its own. */
    public const val ACTION: String = "action"

    /**
     * What changed, as JSON text: a create's fields, an update's `{"field": {"from": …, "to": …}}` for each field
     * that changed, or the fields a delete removed.
     */
    public const val CHANGES: String = "changes"

    /** An action's input, as JSON text. */
    public const val INPUT: String = "input"

    /** The id shared by every entry of one call: a write, or an action and every write it made. */
    public const val OPERATION_ID: String = "operationId"

    /** The values of [OPERATION]. */
    public val OPERATIONS: List<String> = listOf("create", "update", "delete", "action")
}

/**
 * Returns an audit log resource called [name]. Callers with one of [readers] may read it; no one may write it
 * through any API, so its entries can't be changed. Add it to a schema built from data with `resource(...)`.
 */
public fun auditLogResource(name: String = "audit", readers: Set<String> = emptySet()): Resource =
    schema { auditLog(name, readers) }.resource(name)!!

/** Declares the audit log resource that `install(BaseKtAudit)` writes to. See [auditLogResource]. */
public fun SchemaBuilder.auditLog(name: String = "audit", readers: Set<String> = emptySet()) {
    resource(name) {
        instant(AuditFields.AT) { required() }
        string(AuditFields.ACTOR)
        enum(AuditFields.OPERATION, AuditFields.OPERATIONS) { required() }
        string(AuditFields.RESOURCE) { required() }
        string(AuditFields.RECORD_ID)
        string(AuditFields.ACTION)
        string(AuditFields.CHANGES)
        string(AuditFields.INPUT)
        string(AuditFields.OPERATION_ID) { required() }
        permissions { if (readers.isNotEmpty()) read(*readers.toTypedArray()) }
    }
}

/** Returns what keeps [log] from being an audit log, or an empty list. */
internal fun auditLogProblems(log: Resource): List<String> {
    val expected = mapOf(
        AuditFields.AT to FieldType.INSTANT,
        AuditFields.ACTOR to FieldType.STRING,
        AuditFields.OPERATION to FieldType.ENUM,
        AuditFields.RESOURCE to FieldType.STRING,
        AuditFields.RECORD_ID to FieldType.STRING,
        AuditFields.ACTION to FieldType.STRING,
        AuditFields.CHANGES to FieldType.STRING,
        AuditFields.INPUT to FieldType.STRING,
        AuditFields.OPERATION_ID to FieldType.STRING,
    )
    val problems = mutableListOf<String>()
    for ((name, type) in expected) {
        val field = log.field(name)
        when {
            field == null -> problems += "it has no '$name' field"
            field.type != type -> problems += "'$name' is a ${field.type.name.lowercase()} field, not a ${type.name.lowercase()} one"
            field.auto != null -> problems += "'$name' is set by the server"
        }
    }
    val operations = log.field(AuditFields.OPERATION)?.values
    if (operations != null && !operations.containsAll(AuditFields.OPERATIONS)) {
        problems += "'${AuditFields.OPERATION}' must allow ${AuditFields.OPERATIONS.joinToString()}"
    }
    val writable = listOf("create" to log.permissions.create, "update" to log.permissions.update, "delete" to log.permissions.delete)
        .filter { it.second != Access.Nobody }.map { it.first }
    if (writable.isNotEmpty()) problems += "callers may ${writable.joinToString()} its entries"
    if (log.actions.isNotEmpty()) problems += "it has actions"
    return problems
}
