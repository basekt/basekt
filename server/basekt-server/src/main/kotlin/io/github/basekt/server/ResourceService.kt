package io.github.basekt.server

import io.github.basekt.data.AggregatePage
import io.github.basekt.data.AggregateQuery
import io.github.basekt.data.Aggregating
import io.github.basekt.data.Comparison
import io.github.basekt.data.ConditionalUpdate
import io.github.basekt.data.ConditionalWrites
import io.github.basekt.data.DataSource
import io.github.basekt.data.DuplicateIdException
import io.github.basekt.data.Filter
import io.github.basekt.data.InvalidQueryException
import io.github.basekt.data.Page
import io.github.basekt.data.Query
import io.github.basekt.data.Sequencing
import io.github.basekt.schema.ActionTarget
import io.github.basekt.schema.Auto
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
import io.github.basekt.schema.encodeRecord
import io.github.basekt.schema.validateRecord
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Instant

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
    /** Where the times of [Auto.CreatedAt] and [Auto.UpdatedAt] fields come from. */
    private val clock: Clock = Clock.System,
    /** The code of the schema's [io.github.basekt.schema.Action]s. */
    private val actions: Actions = Actions.None,
) {
    private val extensions = CopyOnWriteArrayList<WriteExtension>()

    init {
        val unknown = hooks.resources.filter { schema.resource(it) == null }
        require(unknown.isEmpty()) { "Hooks name resources the schema doesn't have: ${unknown.joinToString()}." }
        val numbered = schema.resources.flatMap { r -> r.fields.filter { it.auto is Auto.Sequence }.map { "${r.name}.${it.name}" } }
        require(numbered.isEmpty() || dataSource is Sequencing) {
            "The sequence fields ${numbered.joinToString()} need a data source that offers Sequencing."
        }
        val versioned = schema.resources.flatMap { r -> r.fields.filter { it.auto == Auto.Version }.map { "${r.name}.${it.name}" } }
        require(versioned.isEmpty() || dataSource is ConditionalWrites) {
            "The version fields ${versioned.joinToString()} need a data source that offers ConditionalWrites."
        }
        val declared = schema.resources.flatMap { r -> r.actions.map { r.name to it.name } }
        val missing = declared.filter { (r, a) -> actions.handler(r, a) == null }
        require(missing.isEmpty()) { "These actions have no code registered: ${missing.joinToString { "${it.first}.${it.second}" }}." }
        val unknownActions = actions.handlers.flatMap { (r, named) -> named.keys.map { r to it } } - declared.toSet()
        require(unknownActions.isEmpty()) {
            "Code is registered for actions the schema doesn't declare: ${unknownActions.joinToString { "${it.first}.${it.second}" }}."
        }
    }

    /** Adds [extension] to every later write. Modules call it when they are installed. */
    public fun extend(extension: WriteExtension) {
        extensions += extension
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

    /**
     * Computes [query]'s grouped totals over the records of [resource] that [caller] may read: their read scope
     * narrows the records first. Throws [ErrorCode.NOT_IMPLEMENTED] if the data source can't aggregate.
     */
    public suspend fun aggregate(resource: Resource, caller: Identity?, query: AggregateQuery): AggregatePage {
        authorize(resource, Operation.READ, caller)
        val source = dataSource as? Aggregating
            ?: throw ApiException(ErrorCode.NOT_IMPLEMENTED, "This data source can't compute aggregates.")
        val scope = scopeFilter(resource, Operation.READ, caller)
        val narrowed = if (scope == null) query else query.copy(filter = query.filter?.let { Filter.And(listOf(it, scope)) } ?: scope)
        return queried { source.aggregate(resource, narrowed) }
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
        val origin = Origin.of(caller)
        return dataSource.transaction { tx -> createIn(tx, resource, origin, decoded) }
            .also { committed(Change.Created(resource, it)) }
    }

    /** Validates [body] and applies it to the record of [resource] with [id], if [caller] may. */
    public suspend fun update(resource: Resource, caller: Identity?, id: String, body: JsonObject): Record {
        authorize(resource, Operation.UPDATE, caller)
        val input = decode(resource, scoped(resource, Operation.UPDATE, caller, body), WriteMode.UPDATE)
        val origin = Origin.of(caller)
        return dataSource.transaction { tx ->
            updateIn(tx, resource, origin, id, input) { writable(tx, resource, Operation.UPDATE, caller, id) }
        }.let { (before, after) ->
            committed(Change.Updated(resource, before, after))
            after
        }
    }

    /** Deletes the record of [resource] with [id], if [caller] may. */
    public suspend fun delete(resource: Resource, caller: Identity?, id: String) {
        authorize(resource, Operation.DELETE, caller)
        val origin = Origin.of(caller)
        val deleted = dataSource.transaction { tx ->
            deleteIn(tx, resource, origin, id) { writable(tx, resource, Operation.DELETE, caller, id) }
        }
        committed(Change.Deleted(resource, deleted))
    }

    /**
     * Runs [resource]'s action [name] for [caller], on the record [id] or, when it is `null`, on the resource as a
     * whole, with the input [body]. Returns the JSON to answer with, or `null` for no content.
     *
     * The caller needs the action's access, and the record must be in their update scope. The input is checked like
     * a record. The action's code runs in one transaction, and the extensions learn of its writes once it commits.
     */
    public suspend fun run(resource: Resource, name: String, caller: Identity?, id: String?, body: JsonObject): JsonObject? {
        val action = resource.action(name)?.takeIf { (it.target == ActionTarget.RECORD) == (id != null) }
            ?: throw ApiException(ErrorCode.NOT_FOUND, "'${resource.name}' has no ${if (id == null) "resource" else "record"} action '$name'.")
        if (!action.access.allows(caller?.roles)) {
            throw if (caller == null) ApiException(ErrorCode.UNAUTHORIZED, "Sign in to run '${resource.name}.$name'.")
            else ApiException(ErrorCode.FORBIDDEN, "Your roles don't allow you to run '${resource.name}.$name'.")
        }
        val handler = checkNotNull(actions.handler(resource.name, name)) { "'${resource.name}.$name' has no code." }
        val inputResource = action.inputResource
        val input = when (val result = inputResource.decodeRecord(body, WriteMode.CREATE, validators)) {
            is DecodeResult.Valid -> result.record
            is DecodeResult.Invalid -> throw ApiException(ApiError(ErrorCode.VALIDATION_FAILED, "The input has invalid fields.", result.errors))
        }
        val changes = mutableListOf<Change>()
        val run = ActionRun(resource, name, id, input)
        val origin = Origin.of(caller, run)
        val answer = dataSource.transaction { tx ->
            val record = id?.let { writable(tx, resource, Operation.UPDATE, caller, it) }
            checkReferences(tx, inputResource, input)
            val answer = ActionContext(resource, caller, record, input, tx, this, changes, origin).handler(input)
            for (extension in extensions) extension.ran(run, origin.context(tx))
            answer
        }
        changes.forEach { committed(it) }
        if (answer != null || id == null) return answer
        return resource.encodeRecord(dataSource.get(resource, id) ?: throw notFound(resource, id))
    }

    /** Creates [record] in [tx] for trusted code, such as an action: every write step but permissions and scopes. */
    internal suspend fun trustedCreate(tx: DataSource, resource: Resource, origin: Origin, record: Record): Record {
        revalidate(resource, record, WriteMode.CREATE)
        return createIn(tx, resource, origin, record)
    }

    /** Applies [changes] in [tx] for trusted code. Returns the record before and after. */
    internal suspend fun trustedUpdate(tx: DataSource, resource: Resource, origin: Origin, id: String, changes: Record): Pair<Record, Record> {
        revalidate(resource, changes, WriteMode.UPDATE)
        return updateIn(tx, resource, origin, id, changes) { tx.get(resource, id) ?: throw notFound(resource, id) }
    }

    /** Deletes in [tx] for trusted code. Returns the deleted record. */
    internal suspend fun trustedDelete(tx: DataSource, resource: Resource, origin: Origin, id: String): Record =
        deleteIn(tx, resource, origin, id) { tx.get(resource, id) ?: throw notFound(resource, id) }

    /** The steps of a create in [tx], once the caller may make it: automatic fields, hooks, checks, the write. */
    private suspend fun createIn(tx: DataSource, resource: Resource, origin: Origin, decoded: Record): Record {
        val caller = origin.caller
        val context = HookContext(resource, caller, tx)
        val stamped = stamped(tx, resource, caller, decoded, WriteMode.CREATE)
        var record = stamped
        for (hook in hooks.beforeCreate[resource.name].orEmpty()) record = context.hook(record).accepted()
        if (record != stamped) revalidate(resource, record, WriteMode.CREATE)
        checkReferences(tx, resource, record)
        checkExtensions(tx, resource, record)
        val created = try {
            tx.create(resource, record)
        } catch (e: DuplicateIdException) {
            throw ApiException(ErrorCode.CONFLICT, e.message!!)
        }
        for (hook in hooks.afterCreate[resource.name].orEmpty()) context.hook(created)
        written(Change.Created(resource, created), origin, tx)
        return created
    }

    /**
     * The steps of an update in [tx], with the record [load] finds: the version check, automatic fields, hooks,
     * checks, the write. Returns the record before and after.
     */
    private suspend fun updateIn(
        tx: DataSource,
        resource: Resource,
        origin: Origin,
        id: String,
        input: Record,
        load: suspend () -> Record,
    ): Pair<Record, Record> {
        val caller = origin.caller
        // The version a client sends is the one it last read, not a value to write.
        val version = resource.fields.firstOrNull { it.auto == Auto.Version }
        val expected = version?.let { input[it.name] }
        val decoded = if (version == null) input else input - version.name
        val context = HookContext(resource, caller, tx)
        val current = load()
        if (version != null && expected != null && current[version.name] != expected) throw stale(resource, id, current[version.name])
        val stamped = stamped(tx, resource, caller, decoded, WriteMode.UPDATE)
        var changes = stamped
        for (hook in hooks.beforeUpdate[resource.name].orEmpty()) {
            changes = context.hook(Update(id, current, changes)).accepted()
        }
        if (changes != stamped) revalidate(resource, changes, WriteMode.UPDATE)
        checkReferences(tx, resource, changes)
        checkExtensions(tx, resource, changes)
        val updated = if (version == null) {
            tx.update(resource, id, changes) ?: throw notFound(resource, id)
        } else {
            versionedUpdate(tx, resource, id, version.name, expected, current, changes)
        }
        for (hook in hooks.afterUpdate[resource.name].orEmpty()) context.hook(Updated(current, updated))
        written(Change.Updated(resource, current, updated), origin, tx)
        return current to updated
    }

    /** The steps of a delete in [tx], with the record [load] finds. Returns the deleted record. */
    private suspend fun deleteIn(tx: DataSource, resource: Resource, origin: Origin, id: String, load: suspend () -> Record): Record {
        val context = HookContext(resource, origin.caller, tx)
        val current = load()
        for (hook in hooks.beforeDelete[resource.name].orEmpty()) context.hook(Deletion(id, current)).accepted()
        if (!tx.delete(resource, id)) throw notFound(resource, id)
        for (hook in hooks.afterDelete[resource.name].orEmpty()) context.hook(current)
        written(Change.Deleted(resource, current), origin, tx)
        return current
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
            // The server sets an automatic field itself, such as the caller's id for ownedBy("createdBy").
            if (resource.field(field)?.auto != null) continue
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

    /**
     * Returns [record] with [resource]'s automatic fields set for a [mode] write by [caller]: the times, the caller's
     * id, and on create the next number of each sequence, taken in the write's transaction [tx].
     */
    private suspend fun stamped(tx: DataSource, resource: Resource, caller: Identity?, record: Record, mode: WriteMode): Record {
        val automatic = resource.fields.filter { it.auto != null }
        if (automatic.isEmpty()) return record
        val creating = mode == WriteMode.CREATE
        // Milliseconds, which every adapter stores exactly.
        val now = Value.Timestamp(Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds()))
        val callerId = caller?.id?.let { Value.Text(it) }
        val stamped = record.toMutableMap()
        for (field in automatic) {
            val value = when (val auto = field.auto!!) {
                Auto.CreatedAt -> now.takeIf { creating }
                Auto.UpdatedAt -> now
                Auto.CreatedBy -> callerId.takeIf { creating }
                Auto.UpdatedBy -> callerId
                Auto.Version -> Value.Integer(1).takeIf { creating }
                is Auto.Sequence -> if (creating) {
                    val number = (tx as Sequencing).nextValue("${resource.name}.${field.name}", auto.start)
                    auto.valueOf(number, field.type)
                } else null
            } ?: continue
            stamped[field.name] = value
        }
        return stamped
    }

    /**
     * Writes [changes] to the record [id] and adds one to its [version] field in one atomic step, if the version is
     * still [expected] (or whatever it is, when the client sent none). A record from before the field existed starts
     * at 1.
     */
    private suspend fun versionedUpdate(
        tx: DataSource,
        resource: Resource,
        id: String,
        version: String,
        expected: Value?,
        current: Record,
        changes: Record,
    ): Record {
        val rest = changes - version
        val condition = expected?.let { Filter.Compare(version, Comparison.EQ, it) }
        val result = if (current[version] == null) {
            (tx as ConditionalWrites).updateIf(resource, id, condition ?: Filter.IsNull(version), rest + (version to Value.Integer(1)))
        } else {
            (tx as ConditionalWrites).updateIf(resource, id, condition, rest, mapOf(version to Value.Integer(1)))
        }
        return when (result) {
            is ConditionalUpdate.Updated -> result.record
            ConditionalUpdate.NotFound -> throw notFound(resource, id)
            ConditionalUpdate.ConditionFailed -> throw stale(resource, id, tx.get(resource, id)?.get(version))
        }
    }

    private fun stale(resource: Resource, id: String, now: Value?) = ApiException(
        ErrorCode.CONFLICT,
        "'${resource.name}/$id' changed since you read it; it is at version ${now ?: "unknown"} now. Reload it and try again.",
    )

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

    private suspend fun checkExtensions(source: DataSource, resource: Resource, record: Record) {
        val errors = extensions.flatMap { it.check(resource, record, source) }
        if (errors.isNotEmpty()) throw invalid(errors)
    }

    /** Tells every extension of a committed write. An extension's failure doesn't undo or fail the write. */
    /** Tells every extension of [change], inside its transaction [tx]. */
    private suspend fun written(change: Change, origin: Origin, tx: DataSource) {
        if (extensions.isEmpty()) return
        val context = origin.context(tx)
        for (extension in extensions) extension.written(change, context)
    }

    private suspend fun committed(change: Change) {
        for (extension in extensions) {
            try {
                extension.committed(change)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The write has happened; the extension reports its own failures.
            }
        }
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
    FieldType.STRING, FieldType.ENUM, FieldType.REFERENCE, FieldType.FILE -> this is Value.Text
    FieldType.INT, FieldType.LONG -> this is Value.Integer
    FieldType.DECIMAL -> this is Value.Decimal
    FieldType.BOOL -> this is Value.Bool
    FieldType.INSTANT -> this is Value.Timestamp
}
