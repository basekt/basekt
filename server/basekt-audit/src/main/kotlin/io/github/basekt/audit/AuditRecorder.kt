package io.github.basekt.audit

import io.github.basekt.schema.Field
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
import io.github.basekt.schema.toJson
import io.github.basekt.server.ActionRun
import io.github.basekt.server.Change
import io.github.basekt.server.WriteContext
import io.github.basekt.server.WriteExtension
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock
import kotlin.time.Instant

/** What a redacted value is logged as. */
internal const val REDACTED = "[redacted]"

/**
 * Writes an entry to [log] for every write and action, in its transaction. [redacted] holds `resource.field` names
 * whose values are logged as [REDACTED]; [skipped] holds resources that aren't logged.
 */
internal class AuditRecorder(
    private val log: Resource,
    private val clock: Clock,
    private val redacted: Set<String>,
    private val skipped: Set<String>,
) : WriteExtension {
    override suspend fun written(change: Change, context: WriteContext) {
        val resource = change.resource
        if (resource.name == log.name || resource.name in skipped) return
        when (change) {
            is Change.Created -> record(context, "create", resource, change.record.id(), changes = fields(resource, change.record))
            is Change.Updated -> record(context, "update", resource, change.after.id(), changes = diff(resource, change.before, change.after))
            is Change.Deleted -> record(context, "delete", resource, change.before.id(), changes = fields(resource, change.before))
        }
    }

    override suspend fun ran(run: ActionRun, context: WriteContext) {
        if (run.resource.name in skipped) return
        val input = run.resource.action(run.action)?.input.orEmpty()
        record(context, "action", run.resource, run.id, input = encode(run.resource, input, run.input))
    }

    private suspend fun record(
        context: WriteContext,
        operation: String,
        resource: Resource,
        id: String?,
        changes: JsonObject? = null,
        input: JsonObject? = null,
    ) {
        val entry = buildMap<String, Value?> {
            put(AuditFields.AT, Value.Timestamp(Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())))
            put(AuditFields.ACTOR, context.caller?.id?.let { Value.Text(it) })
            put(AuditFields.OPERATION, Value.Text(operation))
            put(AuditFields.RESOURCE, Value.Text(resource.name))
            put(AuditFields.RECORD_ID, id?.let { Value.Text(it) })
            put(AuditFields.ACTION, context.action?.action?.let { Value.Text(it) })
            put(AuditFields.CHANGES, changes?.let { Value.Text(it.toString()) })
            put(AuditFields.INPUT, input?.let { Value.Text(it.toString()) })
            put(AuditFields.OPERATION_ID, Value.Text(context.operation))
        }
        context.transaction.create(log, entry)
    }

    /** Every field [record] holds a value for. */
    private fun fields(resource: Resource, record: Record): JsonObject = encode(resource, resource.fields, record)

    /** `{"from": …, "to": …}` for every field whose value changed. */
    private fun diff(resource: Resource, before: Record, after: Record): JsonObject = JsonObject(
        resource.fields.filter { before[it.name] != after[it.name] }.associate { field ->
            field.name to JsonObject(mapOf("from" to value(resource, field, before[field.name]), "to" to value(resource, field, after[field.name])))
        },
    )

    private fun encode(resource: Resource, fields: List<Field>, record: Record): JsonObject = JsonObject(
        fields.filter { record[it.name] != null }.associate { it.name to value(resource, it, record[it.name]) },
    )

    private fun value(resource: Resource, field: Field, value: Value?): JsonElement = when {
        value == null -> JsonNull
        "${resource.name}.${field.name}" in redacted -> JsonPrimitive(REDACTED)
        else -> value.toJson()
    }
}

private fun Record.id(): String = (this[Resource.ID] as Value.Text).value
