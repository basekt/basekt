package io.github.basekt.server

import io.github.basekt.data.Comparison
import io.github.basekt.data.DataSource
import io.github.basekt.data.DuplicateIdException
import io.github.basekt.data.Filter
import io.github.basekt.data.InvalidQueryException
import io.github.basekt.data.Page
import io.github.basekt.data.Query
import io.github.basekt.schema.DecodeResult
import io.github.basekt.schema.FieldError
import io.github.basekt.schema.FieldErrorCode
import io.github.basekt.schema.FieldType
import io.github.basekt.schema.FieldValidator
import io.github.basekt.schema.Operation
import io.github.basekt.schema.Record
import io.github.basekt.schema.Relation
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Scope
import io.github.basekt.schema.Value
import io.github.basekt.schema.WriteMode
import io.github.basekt.schema.decodeRecord
import io.github.basekt.schema.validateRecord
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The operations behind the REST routes, with no Ktor types: permission checks, validation, [hooks], referential
 * integrity and calls to the [dataSource]. Every transport (REST, the admin API, GraphQL...) goes through it, so the
 * rules are the same everywhere.
 *
 * A write runs in one transaction: permission, validation, before-hooks, validation of what they changed, reference
 * checks, the write, then after-hooks. Every function throws [ApiException] when the request can't be served.
 *
 * The resource's [io.github.basekt.schema.Scope]s narrow what a caller reaches: lists only hold records in the
 * caller's read scope, a record outside it is not found, a record the caller may read but not write is forbidden, and
 * a write can't give a scoped field a value outside the caller's. A create that omits a scoped field gets the caller's
 * value when they have exactly one. Scopes check the caller's input; hooks and their [HookContext.data] are trusted.
 */
public class ResourceService(
    /** The schema whose resources are served. */
    public val schema: Schema,
    /** Where the records live. */
    public val dataSource: DataSource,
    /** The validators that [io.github.basekt.schema.Rule.Custom] rules name. */
    private val validators: Map<String, FieldValidator> = emptyMap(),
    /** The code that runs around writes. */
    private val hooks: Hooks = Hooks.None,
) {
    init {
        val unknown = hooks.resources.filter { schema.resource(it) == null }
        require(unknown.isEmpty()) { "Hooks name resources the schema doesn't have: ${unknown.joinToString()}." }
    }

    /** Returns the resource called [name], or throws [ErrorCode.NOT_FOUND]. */
    public fun resource(name: String): Resource =
        schema.resource(name) ?: throw ApiException(ErrorCode.NOT_FOUND, "There is no resource '$name'.")

    /**
     * Returns the condition that keeps [resource]'s records within [caller]'s scopes for [operation], or `null` if no
     * scope limits them. A transport that queries the [dataSource] itself, such as for a count, ANDs it in.
     */
    public fun scopeFilter(resource: Resource, operation: Operation, caller: Identity?): Filter? {
        val filters = scopeValues(resource, operation, caller).map { (scope, values) ->
            if (values.isEmpty()) Filter.Or(emptyList()) else Filter.In(scope.field, values.map { Value.Text(it) })
        }
        return when (filters.size) {
            0 -> null
            1 -> filters.single()
            else -> Filter.And(filters)
        }
    }

    /**
     * Returns the values [caller] may give each of [resource]'s fields that a scope limits for [operation]. A field
     * the caller may set to anything is absent.
     */
    public fun scopedValues(resource: Resource, operation: Operation, caller: Identity?): Map<String, Set<String>> =
        scopeValues(resource, operation, caller).groupBy({ it.first.field }, { it.second })
            .mapValues { (_, sets) -> sets.reduce { all, values -> all intersect values } }

    /** Lists records of [resource] that match [query], if [caller] may read them. */
    public suspend fun list(resource: Resource, caller: Identity?, query: Query): Page {
        authorize(resource, Operation.READ, caller)
        return queried { dataSource.list(resource, query.narrowed(scopeFilter(resource, Operation.READ, caller))) }
    }

    /** Returns the record of [resource] with [id], if [caller] may read it. */
    public suspend fun get(resource: Resource, caller: Identity?, id: String): Record {
        authorize(resource, Operation.READ, caller)
        return readable(dataSource, resource, caller, id)
    }

    /** Validates [body] and creates a record of [resource] from it, if [caller] may. */
    public suspend fun create(resource: Resource, caller: Identity?, body: JsonObject): Record {
        authorize(resource, Operation.CREATE, caller)
        val decoded = decode(resource, scoped(resource, Operation.CREATE, caller, body), WriteMode.CREATE)
        return dataSource.transaction { tx ->
            val context = HookContext(resource, caller, tx)
            var record = decoded
            for (hook in hooks.beforeCreate[resource.name].orEmpty()) record = context.hook(record).accepted()
            if (record != decoded) revalidate(resource, record, WriteMode.CREATE)
            checkReferences(tx, resource, record)
            val created = try {
                tx.create(resource, record)
            } catch (e: DuplicateIdException) {
                throw ApiException(ErrorCode.CONFLICT, e.message!!)
            }
            for (hook in hooks.afterCreate[resource.name].orEmpty()) context.hook(created)
            created
        }
    }

    /** Validates [body] and applies it to the record of [resource] with [id], if [caller] may. */
    public suspend fun update(resource: Resource, caller: Identity?, id: String, body: JsonObject): Record {
        authorize(resource, Operation.UPDATE, caller)
        val decoded = decode(resource, scoped(resource, Operation.UPDATE, caller, body), WriteMode.UPDATE)
        return dataSource.transaction { tx ->
            val context = HookContext(resource, caller, tx)
            val current = writable(tx, resource, Operation.UPDATE, caller, id)
            var changes = decoded
            for (hook in hooks.beforeUpdate[resource.name].orEmpty()) {
                changes = context.hook(Update(id, current, changes)).accepted()
            }
            if (changes != decoded) revalidate(resource, changes, WriteMode.UPDATE)
            checkReferences(tx, resource, changes)
            val updated = tx.update(resource, id, changes) ?: throw notFound(resource, id)
            for (hook in hooks.afterUpdate[resource.name].orEmpty()) context.hook(Updated(current, updated))
            updated
        }
    }

    /** Deletes the record of [resource] with [id], if [caller] may. */
    public suspend fun delete(resource: Resource, caller: Identity?, id: String) {
        authorize(resource, Operation.DELETE, caller)
        dataSource.transaction { tx ->
            val context = HookContext(resource, caller, tx)
            val current = writable(tx, resource, Operation.DELETE, caller, id)
            for (hook in hooks.beforeDelete[resource.name].orEmpty()) context.hook(Deletion(id, current)).accepted()
            if (!tx.delete(resource, id)) throw notFound(resource, id)
            for (hook in hooks.afterDelete[resource.name].orEmpty()) context.hook(current)
        }
    }

    /** Returns the one record that the record [id] of [resource] belongs to through [relation]. */
    public suspend fun parent(resource: Resource, caller: Identity?, id: String, relation: Relation.BelongsTo): Record {
        val target = resource(relation.target)
        authorize(target, Operation.READ, caller)
        val record = get(resource, caller, id)
        val parentId = (record[relation.field] as Value.Text?)?.value
            ?: throw ApiException(ErrorCode.NOT_FOUND, "'${resource.name}/$id' has no '${relation.name}'.")
        return readable(dataSource, target, caller, parentId)
    }

    /** Lists the records of [relation]'s target that point to the record [id] of [resource], matching [query]. */
    public suspend fun children(
        resource: Resource,
        caller: Identity?,
        id: String,
        relation: Relation.HasMany,
        query: Query,
    ): Page {
        val target = resource(relation.target)
        authorize(target, Operation.READ, caller)
        get(resource, caller, id)
        val link = Filter.Compare(relation.foreignField, Comparison.EQ, Value.Text(id))
        val narrowed = query.narrowed(link).narrowed(scopeFilter(target, Operation.READ, caller))
        return queried { dataSource.list(target, narrowed) }
    }

    private fun scopeValues(resource: Resource, operation: Operation, caller: Identity?): List<Pair<Scope, Set<String>>> =
        resource.permissions.scopes.mapNotNull { scope ->
            scope.valuesFor(operation, caller?.id, caller?.roles.orEmpty(), caller?.attributes.orEmpty())?.let { scope to it }
        }

    private fun inScope(resource: Resource, operation: Operation, caller: Identity?, record: Record): Boolean =
        scopeValues(resource, operation, caller).all { (scope, values) -> (record[scope.field] as? Value.Text)?.value in values }

    /** Returns the record of [resource] with [id] if it is in [caller]'s read scope, and throws not found otherwise. */
    private suspend fun readable(source: DataSource, resource: Resource, caller: Identity?, id: String): Record {
        val record = source.get(resource, id)
        if (record == null || !inScope(resource, Operation.READ, caller, record)) throw notFound(resource, id)
        return record
    }

    /** Returns the record [readable] finds, and throws forbidden if it is outside [caller]'s scope for [operation]. */
    private suspend fun writable(source: DataSource, resource: Resource, operation: Operation, caller: Identity?, id: String): Record {
        val record = readable(source, resource, caller, id)
        if (!inScope(resource, operation, caller, record)) {
            throw ApiException(ErrorCode.FORBIDDEN, "'${resource.name}/$id' is outside the records you may ${operation.name.lowercase()}.")
        }
        return record
    }

    /**
     * Checks the scoped fields of a create or update [body] against [caller]'s values, and fills in a create's missing
     * scoped field when the caller has exactly one value. A value of the wrong type is left for decoding to report.
     */
    private fun scoped(resource: Resource, operation: Operation, caller: Identity?, body: JsonObject): JsonObject {
        val scoped = scopedValues(resource, operation, caller)
        if (scoped.isEmpty()) return body
        val filled = body.toMutableMap()
        for ((field, values) in scoped) {
            val given = body[field]
            if (given == null && operation == Operation.UPDATE) continue
            if (given == null || given == JsonNull) {
                when {
                    values.isEmpty() -> throw ApiException(
                        ErrorCode.FORBIDDEN,
                        "You have no value for '$field', so you can't ${operation.name.lowercase()} '${resource.name}' records.",
                    )
                    operation != Operation.CREATE -> throw outsideScope(resource, field)
                    values.size == 1 -> filled[field] = JsonPrimitive(values.single())
                    else -> throw invalid(
                        listOf(FieldError(field, FieldErrorCode.REQUIRED, "Choose one of your values: ${values.sorted().joinToString()}.")),
                    )
                }
                continue
            }
            val text = (given as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            if (text !in values) throw outsideScope(resource, field)
        }
        return JsonObject(filled)
    }

    private fun outsideScope(resource: Resource, field: String) =
        ApiException(ErrorCode.FORBIDDEN, "You can't give '${resource.name}.$field' a value outside your own.")

    private fun authorize(resource: Resource, operation: Operation, caller: Identity?) {
        if (resource.permissions.of(operation).allows(caller?.roles)) return
        val verb = operation.name.lowercase()
        throw if (caller == null) {
            ApiException(ErrorCode.UNAUTHORIZED, "Sign in to $verb '${resource.name}'.")
        } else {
            ApiException(ErrorCode.FORBIDDEN, "Your roles don't allow you to $verb '${resource.name}'.")
        }
    }

    private fun decode(resource: Resource, body: JsonObject, mode: WriteMode): Record =
        when (val result = resource.decodeRecord(body, mode, validators)) {
            is DecodeResult.Valid -> result.record
            is DecodeResult.Invalid -> throw invalid(result.errors)
        }

    /** Checks what before-hooks produced: field names and value types, then the schema's rules. */
    private fun revalidate(resource: Resource, record: Record, mode: WriteMode) {
        for ((name, value) in record) {
            if (name == Resource.ID && mode == WriteMode.CREATE) {
                check(value is Value.Text) { "A hook on '${resource.name}' set a non-text id." }
                continue
            }
            val field = checkNotNull(resource.field(name)) { "A hook on '${resource.name}' set the unknown field '$name'." }
            check(value == null || value.fits(field.type)) {
                "A hook on '${resource.name}' set '$name' to '$value', which isn't a ${field.type.name.lowercase()}."
            }
        }
        val errors = resource.validateRecord(record, mode, validators)
        if (errors.isNotEmpty()) throw invalid(errors)
    }

    private suspend fun checkReferences(source: DataSource, resource: Resource, record: Record) {
        val missing = resource.fields.filter { it.type == FieldType.REFERENCE }.mapNotNull { field ->
            val id = (record[field.name] as Value.Text?)?.value ?: return@mapNotNull null
            if (source.get(resource(field.target!!), id) != null) null
            else FieldError(field.name, FieldErrorCode.MISSING_REFERENCE, "There is no '${field.target}' with id '$id'.")
        }
        if (missing.isNotEmpty()) throw invalid(missing)
    }

    private inline fun <T> queried(block: () -> T): T = try {
        block()
    } catch (e: InvalidQueryException) {
        throw ApiException(ErrorCode.INVALID_QUERY, e.message!!)
    }

    private fun <T> Verdict<T>.accepted(): T = when (this) {
        is Verdict.Proceed -> value
        is Verdict.Veto -> throw if (fields.isEmpty()) ApiException(ErrorCode.REJECTED, reason)
        else ApiException(ApiError(ErrorCode.VALIDATION_FAILED, reason, fields))
    }

    private fun invalid(errors: List<FieldError>) =
        ApiException(ApiError(ErrorCode.VALIDATION_FAILED, "The record has invalid fields.", errors))

    private fun notFound(resource: Resource, id: String) =
        ApiException(ErrorCode.NOT_FOUND, "There is no '${resource.name}' with id '$id'.")
}

/** Returns this query with [filter] ANDed into its own. */
private fun Query.narrowed(filter: Filter?): Query =
    if (filter == null) this else copy(filter = this.filter?.let { Filter.And(listOf(it, filter)) } ?: filter)

private fun Value.fits(type: FieldType): Boolean = when (type) {
    FieldType.STRING, FieldType.ENUM, FieldType.REFERENCE -> this is Value.Text
    FieldType.INT, FieldType.LONG -> this is Value.Integer
    FieldType.DECIMAL -> this is Value.Decimal
    FieldType.BOOL -> this is Value.Bool
    FieldType.INSTANT -> this is Value.Timestamp
}
