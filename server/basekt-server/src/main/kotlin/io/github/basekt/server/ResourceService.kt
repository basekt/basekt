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
import io.github.basekt.schema.IdSource
import io.github.basekt.schema.IdSpec
import io.github.basekt.schema.OnDelete
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
import io.ktor.util.logging.KtorSimpleLogger
import io.ktor.util.logging.Logger
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
 *
 * A [Resource.provided] resource's records come from its [RecordProvider] instead of the data source, with the same
 * permissions, scopes and actions. Every write to one is refused with [ErrorCode.READ_ONLY], and a provider that fails
 * answers [ErrorCode.UNAVAILABLE].
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
    /** Where the failures of after-commit hooks are logged. */
    private val log: Logger = KtorSimpleLogger("io.github.basekt.server.ResourceService"),
    /** The code that serves each [Resource.provided] resource's records, by resource name. */
    private val providers: Map<String, RecordProvider> = emptyMap(),
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
        // A transition's code is optional: it guards the transition and acts beside it.
        val runnable = schema.resources.flatMap { r -> r.runnableActions.map { r.name to it.name } }.toSet()
        val unknownActions = actions.handlers.flatMap { (r, named) -> named.keys.map { r to it } } - runnable
        require(unknownActions.isEmpty()) {
            "Code is registered for actions the schema doesn't declare: ${unknownActions.joinToString { "${it.first}.${it.second}" }}."
        }
        val workflows = schema.resources.filter { it.workflow != null }.map { it.name }
        require(workflows.isEmpty() || dataSource is ConditionalWrites) {
            "The workflows of ${workflows.joinToString()} need a data source that offers ConditionalWrites."
        }
        val problems = schema.providerProblems(providers) +
            hooks.resources.filter { schema.resource(it)?.provided == true }.map { "Hooks name '$it', which is provided() and never written." }
        require(problems.isEmpty()) { "The provided resources are not set up right:\n" + problems.joinToString("\n") { "- $it" } }
    }

    /** Returns the provider of [resource], or `null` if its records live in the data source. */
    public fun provider(resource: Resource): RecordProvider? = if (resource.provided) providers[resource.name] else null

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
        val filters = scopeClauses(resource, operation, caller).map { clauses ->
            val ways = clauses.filter { (_, values) -> values.isNotEmpty() }.map { (field, values) ->
                Filter.In(field, values.map { Value.Text(it) })
            }
            when (ways.size) {
                0 -> Filter.Or(emptyList())
                1 -> ways.single()
                else -> Filter.Or(ways)
            }
        }
        return when (filters.size) {
            0 -> null
            1 -> filters.single()
            else -> Filter.And(filters)
        }
    }

    /**
     * Returns the values [caller] may give each of [resource]'s fields that a scope limits for [operation]. A field
     * the caller may set to anything is absent, and so is a field of a scope with several ways in: those are checked
     * together, as a whole, when the record is written.
     */
    public fun scopedValues(resource: Resource, operation: Operation, caller: Identity?): Map<String, Set<String>> =
        scopeClauses(resource, operation, caller).filter { it.size == 1 }.map { it.single() }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, sets) -> sets.reduce { all, values -> all intersect values } }

    /** Lists records of [resource] that match [query], if [caller] may read them. */
    public suspend fun list(resource: Resource, caller: Identity?, query: Query): Page {
        authorize(resource, Operation.READ, caller)
        provider(resource)?.let { return providedList(resource, it, caller, query) }
        return queried { dataSource.list(resource, query.narrowed(scopeFilter(resource, Operation.READ, caller))) }
    }

    /**
     * Computes [query]'s grouped totals over the records of [resource] that [caller] may read: their read scope
     * narrows the records first. Throws [ErrorCode.NOT_IMPLEMENTED] if the data source can't aggregate.
     */
    public suspend fun aggregate(resource: Resource, caller: Identity?, query: AggregateQuery): AggregatePage {
        authorize(resource, Operation.READ, caller)
        if (resource.provided) throw ApiException(ErrorCode.NOT_IMPLEMENTED, "'${resource.name}' is provided by the application's code, which can't compute aggregates.")
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
        refuseWrite(resource)
        authorize(resource, Operation.CREATE, caller)
        val decoded = decode(resource, scoped(resource, Operation.CREATE, caller, body), WriteMode.CREATE)
        val origin = Origin.of(caller)
        return dataSource.transaction { tx ->
            createIn(tx, resource, origin, decoded).also { checkInheritedScope(resource, caller, it) }
        }.also { committed(Change.Created(resource, it), origin) }
    }

    /** Validates [body] and applies it to the record of [resource] with [id], if [caller] may. */
    public suspend fun update(resource: Resource, caller: Identity?, id: String, body: JsonObject): Record {
        refuseWrite(resource)
        authorize(resource, Operation.UPDATE, caller)
        val input = decode(resource, scoped(resource, Operation.UPDATE, caller, body), WriteMode.UPDATE)
        val origin = Origin.of(caller)
        return dataSource.transaction { tx ->
            updateIn(tx, resource, origin, id, input) {
                writable(tx, resource, Operation.UPDATE, caller, id).also { staysInScope(resource, caller, it, input) }
            }
        }.let { (before, after) ->
            committed(Change.Updated(resource, before, after), origin)
            after
        }
    }

    /**
     * Deletes the record of [resource] with [id], if [caller] may, with what its [Relation.BelongsTo] referrers'
     * [OnDelete] rules say: refused while a restricting one points to it, and otherwise cascaded or cleared in the same
     * transaction. The caller's permission on this record covers those other writes.
     */
    public suspend fun delete(resource: Resource, caller: Identity?, id: String) {
        refuseWrite(resource)
        authorize(resource, Operation.DELETE, caller)
        val origin = Origin.of(caller)
        val changes = dataSource.transaction { tx ->
            deleteIn(tx, resource, origin, id) { writable(tx, resource, Operation.DELETE, caller, id) }
        }
        changes.forEach { committed(it, origin) }
    }

    /**
     * Runs [resource]'s action [name] for [caller], on the record [id] or, when it is `null`, on the resource as a
     * whole, with the input [body]. Returns the JSON to answer with, or `null` for no content.
     *
     * The caller needs the action's access, and the record must be in their update scope. The input is checked like
     * a record. The action's code runs in one transaction, and the extensions learn of its writes once it commits.
     * On a [Resource.provided] resource, the record comes from its provider, and the action writes other resources.
     */
    public suspend fun run(resource: Resource, name: String, caller: Identity?, id: String?, body: JsonObject): JsonObject? {
        val transition = resource.transition(name)
        val action = (resource.action(name) ?: transition?.asAction())?.takeIf { (it.target == ActionTarget.RECORD) == (id != null) }
            ?: throw ApiException(ErrorCode.NOT_FOUND, "'${resource.name}' has no ${if (id == null) "resource" else "record"} action '$name'.")
        if (!action.access.allows(caller?.roles)) {
            throw if (caller == null) ApiException(ErrorCode.UNAUTHORIZED, "Sign in to run '${resource.name}.$name'.")
            else ApiException(ErrorCode.FORBIDDEN, "Your roles don't allow you to run '${resource.name}.$name'.")
        }
        val handler = actions.handler(resource.name, name)
        check(handler != null || transition != null) { "'${resource.name}.$name' has no code." }
        val inputResource = action.inputResource
        val input = when (val result = inputResource.decodeRecord(body, WriteMode.CREATE, validators)) {
            is DecodeResult.Valid -> result.record
            is DecodeResult.Invalid -> throw ApiException(ApiError(ErrorCode.VALIDATION_FAILED, "The input has invalid fields.", result.errors))
        }
        val changes = mutableListOf<Change>()
        val run = ActionRun(resource, name, id, input)
        val origin = Origin.of(caller, run)
        val answer = dataSource.transaction { tx ->
            // A source may run the block again after a conflict: only the last run's changes are reported.
            changes.clear()
            val record = id?.let { writable(tx, resource, Operation.UPDATE, caller, it) }
            checkReferences(tx, inputResource, input)
            val state = transition?.let { checkState(resource, it, id!!, record!!) }
            val context = ActionContext(resource, caller, record, input, tx, this, changes, origin)
            val answer = handler?.let { context.it(input) }
            if (transition != null) {
                // Only if no other transition moved the record since it was read.
                val field = resource.workflow!!.field
                val (before, after) = updateIn(tx, resource, origin, id!!, mapOf(field to Value.Text(transition.to)), Filter.Compare(field, Comparison.EQ, state!!)) {
                    tx.get(resource, id) ?: throw notFound(resource, id)
                }
                changes += Change.Updated(resource, before, after)
            }
            for (extension in extensions) extension.ran(run, origin.context(tx))
            answer
        }
        changes.forEach { committed(it, origin) }
        if (answer != null || id == null) return answer
        val provider = provider(resource)
        val after = if (provider == null) dataSource.get(resource, id) else providedGet(resource, provider, id)
        return resource.encodeRecord(after ?: throw notFound(resource, id))
    }

    /** Creates [record] in [tx] for trusted code, such as an action: every write step but permissions and scopes. */
    internal suspend fun trustedCreate(tx: DataSource, resource: Resource, origin: Origin, record: Record): Record {
        refuseWrite(resource)
        revalidate(resource, record, WriteMode.CREATE)
        return createIn(tx, resource, origin, record)
    }

    /** Applies [changes] in [tx] for trusted code. Returns the record before and after. */
    internal suspend fun trustedUpdate(tx: DataSource, resource: Resource, origin: Origin, id: String, changes: Record): Pair<Record, Record> {
        refuseWrite(resource)
        revalidate(resource, changes, WriteMode.UPDATE)
        return updateIn(tx, resource, origin, id, changes) { tx.get(resource, id) ?: throw notFound(resource, id) }
    }

    /** Deletes in [tx] for trusted code, with the delete's cascades. Returns every change it made. */
    internal suspend fun trustedDelete(tx: DataSource, resource: Resource, origin: Origin, id: String): List<Change> {
        refuseWrite(resource)
        return deleteIn(tx, resource, origin, id) { tx.get(resource, id) ?: throw notFound(resource, id) }
    }

    /** Returns the ids of every record of [resource] that matches [filter] in [source], page by page. */
    internal suspend fun idsWhere(source: DataSource, resource: Resource, filter: Filter?): List<String> {
        refuseWrite(resource)
        val ids = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = queried { source.list(resource, Query(filter = filter, limit = SCAN_PAGE, cursor = cursor)) }
            page.items.mapTo(ids) { (it[Resource.ID] as Value.Text).value }
            cursor = page.nextCursor
        } while (cursor != null)
        return ids
    }

    /** The steps of a create in [tx], once the caller may make it: automatic fields, hooks, checks, the write. */
    private suspend fun createIn(tx: DataSource, resource: Resource, origin: Origin, decoded: Record): Record {
        val caller = origin.caller
        val context = HookContext(resource, caller, tx)
        val stamped = stamped(tx, resource, caller, decoded, WriteMode.CREATE)
        var record = stamped
        for (hook in hooks.beforeCreate[resource.name].orEmpty()) record = context.hook(record).accepted()
        if (record != stamped) revalidate(resource, record, WriteMode.CREATE)
        if (resource.id.source == IdSource.KEY) record = record + (Resource.ID to Value.Text(keyOf(resource, record)))
        checkReferences(tx, resource, record)
        checkUnique(tx, resource, record, null)
        checkExtensions(tx, resource, record)
        val created = try {
            tx.create(resource, record)
        } catch (e: DuplicateIdException) {
            val key = resource.id.key
            throw ApiException(
                ErrorCode.CONFLICT,
                if (key.isEmpty()) e.message!! else "There is already a '${resource.name}' with this ${key.joinToString(" and ")}.",
            )
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
        condition: Filter? = null,
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
        val moved = resource.id.key.filter { it in changes && changes[it] != current[it] }
        if (moved.isNotEmpty()) throw invalid(moved.map { FieldError(it, FieldErrorCode.READ_ONLY, "'$it' is part of the id, so it can't change.") })
        checkReferences(tx, resource, changes)
        checkUnique(tx, resource, changes, id)
        checkExtensions(tx, resource, changes)
        val updated = when {
            version != null -> versionedUpdate(tx, resource, id, version.name, expected, current, changes, condition)
            condition != null -> when (val result = (tx as ConditionalWrites).updateIf(resource, id, condition, changes)) {
                is ConditionalUpdate.Updated -> result.record
                ConditionalUpdate.NotFound -> throw notFound(resource, id)
                ConditionalUpdate.ConditionFailed -> throw moved(resource, id)
            }
            else -> tx.update(resource, id, changes) ?: throw notFound(resource, id)
        }
        for (hook in hooks.afterUpdate[resource.name].orEmpty()) context.hook(Updated(current, updated))
        written(Change.Updated(resource, current, updated), origin, tx)
        return current to updated
    }

    /**
     * The steps of a delete in [tx], with the record [load] finds, and of the deletes and updates its referrers'
     * [OnDelete] rules call for. First every record the delete reaches is found and every restriction checked, then
     * the referrers to clear are updated, then each record is deleted with its hooks, the first one first. Returns
     * every change, in that order.
     */
    private suspend fun deleteIn(tx: DataSource, resource: Resource, origin: Origin, id: String, load: suspend () -> Record): List<Change> {
        val doomed = linkedMapOf((resource.name to id) to (resource to load()))
        val clears = linkedMapOf<Pair<String, String>, Pair<Resource, MutableSet<String>>>()
        val restricted = mutableListOf<Restriction>()
        val queue = ArrayDeque(listOf(resource to id))
        while (queue.isNotEmpty()) {
            val (parent, parentId) = queue.removeFirst()
            for ((child, relation) in referrers[parent.name].orEmpty()) {
                val ids = idsWhere(tx, child, Filter.Compare(relation.field, Comparison.EQ, Value.Text(parentId)))
                when (relation.onDelete) {
                    OnDelete.RESTRICT -> restricted += Restriction("${parent.name}/$parentId", child, relation, ids)
                    OnDelete.SET_NULL -> for (childId in ids) {
                        clears.getOrPut(child.name to childId) { child to mutableSetOf() }.second += relation.field
                    }
                    OnDelete.CASCADE -> for (childId in ids) {
                        if ((child.name to childId) in doomed) continue
                        doomed[child.name to childId] = child to (tx.get(child, childId) ?: continue)
                        queue += child to childId
                    }
                }
            }
        }
        // A record that the same delete removes doesn't hold it back.
        for (restriction in restricted) {
            val blocking = restriction.ids.filter { (restriction.child.name to it) !in doomed }
            if (blocking.isEmpty()) continue
            val one = blocking.size == 1
            throw ApiException(
                ErrorCode.CONFLICT,
                "'${restriction.parent}' can't be deleted while ${blocking.size} '${restriction.child.name}' record${if (one) "" else "s"} " +
                    "point${if (one) "s" else ""} to it through '${restriction.relation.name}', such as '${blocking.first()}'.",
            )
        }
        val changes = mutableListOf<Change>()
        for ((key, cleared) in clears) {
            if (key in doomed) continue
            val (child, fields) = cleared
            val (before, after) = updateIn(tx, child, origin, key.second, fields.associateWith { null }) {
                tx.get(child, key.second) ?: throw notFound(child, key.second)
            }
            changes += Change.Updated(child, before, after)
        }
        for ((key, doomedRecord) in doomed) {
            val (target, current) = doomedRecord
            val context = HookContext(target, origin.caller, tx)
            for (hook in hooks.beforeDelete[target.name].orEmpty()) context.hook(Deletion(key.second, current)).accepted()
            if (!tx.delete(target, key.second)) throw notFound(target, key.second)
            for (hook in hooks.afterDelete[target.name].orEmpty()) context.hook(current)
            written(Change.Deleted(target, current), origin, tx)
            changes += Change.Deleted(target, current)
        }
        return changes
    }

    /** The records of [child] whose [relation] points to [parent], which hold its delete back. */
    private class Restriction(val parent: String, val child: Resource, val relation: Relation.BelongsTo, val ids: List<String>)

    /** Every [Relation.BelongsTo] in the schema, with the resource that has it, by the relation's target. */
    private val referrers: Map<String, List<Pair<Resource, Relation.BelongsTo>>> = schema.resources
        .flatMap { child -> child.relations.filterIsInstance<Relation.BelongsTo>().map { child to it } }
        .groupBy { it.second.target }

    /** Returns the id [resource]'s key fields make of [record], or refuses the record if one is unset. */
    private fun keyOf(resource: Resource, record: Record): String {
        val missing = resource.id.key.filter { record[it] == null }
        if (missing.isNotEmpty()) {
            throw invalid(missing.map { FieldError(it, FieldErrorCode.REQUIRED, "'$it' is part of the id, so it is required.") })
        }
        val id = resource.id.keyOf(record)!!
        IdSpec.problemWith(id)?.let { problem ->
            throw invalid(listOf(FieldError(Resource.ID, FieldErrorCode.TYPE, "The id ${resource.id.key.joinToString(" and ")} make is invalid. $problem")))
        }
        return id
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

    /** Returns, for each scope that applies to [caller], the field and allowed values of each of its ways in. */
    private fun scopeClauses(resource: Resource, operation: Operation, caller: Identity?): List<List<Pair<String, Set<String>>>> =
        resource.permissions.scopes.mapNotNull { scope ->
            scope.clauseValuesFor(operation, caller?.id, caller?.roles.orEmpty(), caller?.attributes.orEmpty())
        }

    private fun inScope(resource: Resource, operation: Operation, caller: Identity?, record: Record): Boolean =
        scopeClauses(resource, operation, caller).all { clauses -> clauses.any { (field, values) -> (record[field] as? Value.Text)?.value in values } }

    /** Returns the record of [resource] with [id] if it is in [caller]'s read scope, and throws not found otherwise. */
    private suspend fun readable(source: DataSource, resource: Resource, caller: Identity?, id: String): Record {
        val provider = provider(resource)
        val record = if (provider == null) source.get(resource, id) else providedGet(resource, provider, id)
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
        val anyOf = scopeClauses(resource, operation, caller).filter { it.size > 1 }
        if (scoped.isEmpty() && anyOf.isEmpty()) return body
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
        if (operation == Operation.CREATE) for (clauses in anyOf) checkAnyOf(resource, clauses, body)
        return JsonObject(filled)
    }

    /**
     * Refuses a create whose inherited scope field, copied from a parent the caller named, puts the new record outside
     * the caller's create scope. The transaction rolls the record back.
     */
    private fun checkInheritedScope(resource: Resource, caller: Identity?, created: Record) {
        for (clauses in scopeClauses(resource, Operation.CREATE, caller)) {
            if (clauses.none { (field, _) -> resource.field(field)?.auto is Auto.Inherited }) continue
            if (clauses.none { (field, values) -> (created[field] as? Value.Text)?.value in values }) {
                throw ApiException(
                    ErrorCode.FORBIDDEN,
                    "You can't create '${resource.name}' records under a parent outside your own values of ${clauses.joinToString { it.first }}.",
                )
            }
        }
    }

    /**
     * Checks a create [body] against a scope with several ways in: it must give at least one of the fields a value the
     * caller has. An update is checked against the whole record it produces, by [staysInScope].
     */
    private fun checkAnyOf(resource: Resource, clauses: List<Pair<String, Set<String>>>, body: JsonObject) {
        val names = clauses.joinToString { it.first }
        // The server sets an automatic field itself, so it can't be outside the caller's values.
        if (clauses.any { resource.field(it.first)?.auto != null }) return
        val given = clauses.mapNotNull { (field, values) ->
            val text = (body[field] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            text in values
        }
        when {
            clauses.all { it.second.isEmpty() } -> throw ApiException(
                ErrorCode.FORBIDDEN,
                "You have no value for any of $names, so you can't create '${resource.name}' records.",
            )
            given.isEmpty() ->
                throw invalid(listOf(FieldError(clauses.first().first, FieldErrorCode.REQUIRED, "Give one of $names a value that is yours.")))
            given.none { it } -> throw ApiException(
                ErrorCode.FORBIDDEN,
                "You can't create '${resource.name}' with none of $names inside your own values.",
            )
        }
    }

    /**
     * Refuses an update of [current] by [input] that would leave a scope with several ways in: the record the update
     * produces must still be reachable by one of them.
     */
    private fun staysInScope(resource: Resource, caller: Identity?, current: Record, input: Record) {
        val anyOf = scopeClauses(resource, Operation.UPDATE, caller).filter { it.size > 1 }
        if (anyOf.isEmpty()) return
        val after = current + input
        for (clauses in anyOf) {
            if (clauses.none { (field, values) -> (after[field] as? Value.Text)?.value in values }) {
                throw ApiException(
                    ErrorCode.FORBIDDEN,
                    "You can't move '${resource.name}/${current[Resource.ID]}' out of your own values of ${clauses.joinToString { it.first }}.",
                )
            }
        }
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
                Auto.State -> resource.workflow?.let { Value.Text(it.initial) }?.takeIf { creating }
                is Auto.Inherited -> if (creating) {
                    val parent = (record[auto.via] as? Value.Text)?.value
                    val target = resource.field(auto.via)?.target
                    if (parent == null || target == null) null else tx.get(resource(target), parent)?.get(auto.field)
                } else null
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
        extra: Filter? = null,
    ): Record {
        val rest = changes - version
        fun and(condition: Filter?): Filter? = listOfNotNull(condition, extra).let { if (it.size > 1) Filter.And(it) else it.firstOrNull() }
        val condition = expected?.let { Filter.Compare(version, Comparison.EQ, it) }
        val result = if (current[version] == null) {
            (tx as ConditionalWrites).updateIf(resource, id, and(condition ?: Filter.IsNull(version)), rest + (version to Value.Integer(1)))
        } else {
            (tx as ConditionalWrites).updateIf(resource, id, and(condition), rest, mapOf(version to Value.Integer(1)))
        }
        return when (result) {
            is ConditionalUpdate.Updated -> result.record
            ConditionalUpdate.NotFound -> throw notFound(resource, id)
            ConditionalUpdate.ConditionFailed -> throw if (extra != null) moved(resource, id) else stale(resource, id, tx.get(resource, id)?.get(version))
        }
    }

    /** Returns the state of [record], and throws a conflict if [transition] can't start from it. */
    private fun checkState(resource: Resource, transition: io.github.basekt.schema.Transition, id: String, record: Record): Value {
        val state = record[resource.workflow!!.field]
        val name = (state as? Value.Text)?.value
        if (name == null || name !in transition.from) {
            throw ApiException(
                ErrorCode.CONFLICT,
                "'${resource.name}/$id' is ${name ?: "in no state"}, so it can't '${transition.name}': that needs ${transition.from.joinToString(" or ")}.",
            )
        }
        return state
    }

    private fun moved(resource: Resource, id: String) =
        ApiException(ErrorCode.CONFLICT, "'${resource.name}/$id' changed while this ran. Reload it and try again.")

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
        val missing = resource.fields.filter { it.type == FieldType.REFERENCE }.flatMap { field ->
            val ids = when (val value = record[field.name]) {
                null -> emptyList()
                is Value.Items -> value.values.map { (it as Value.Text).value }
                else -> listOf((value as Value.Text).value)
            }
            ids.mapNotNull { id ->
                if (source.get(resource(field.target!!), id) != null) null
                else FieldError(field.name, FieldErrorCode.MISSING_REFERENCE, "There is no '${field.target}' with id '$id'.")
            }
        }
        if (missing.isNotEmpty()) throw invalid(missing)
    }

    /**
     * Refuses a write of [record] that gives a unique field a value another record than [id] already holds. Checked in
     * the write's transaction, so a data source that also keeps a unique index is only a second line of defence.
     */
    private suspend fun checkUnique(source: DataSource, resource: Resource, record: Record, id: String?) {
        val clashes = resource.fields.filter { it.unique }.mapNotNull { field ->
            val value = record[field.name] ?: return@mapNotNull null
            val filter = Filter.Compare(field.name, Comparison.EQ, value)
            val others = source.list(resource, Query(filter = filter, limit = 2)).items.filter { it[Resource.ID] != id?.let(Value::Text) }
            if (others.isEmpty()) null
            else FieldError(field.name, FieldErrorCode.UNIQUE, "Another '${resource.name}' already has ${field.name} '${value}'.")
        }
        if (clashes.isNotEmpty()) throw invalid(clashes)
    }

    private suspend fun checkExtensions(source: DataSource, resource: Resource, record: Record) {
        val errors = extensions.flatMap { it.check(resource, record, source) }
        if (errors.isNotEmpty()) throw invalid(errors)
    }

    /** Tells every extension of [change], inside its transaction [tx]. */
    private suspend fun written(change: Change, origin: Origin, tx: DataSource) {
        if (extensions.isEmpty()) return
        val context = origin.context(tx)
        for (extension in extensions) extension.written(change, context)
    }

    /**
     * Tells every extension, then every after-commit hook, of a committed write. A failure doesn't undo or fail the
     * write: an extension reports its own, and a hook's is logged with the request id.
     */
    private suspend fun committed(change: Change, origin: Origin) {
        for (extension in extensions) {
            try {
                extension.committed(change)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The write has happened; the extension reports its own failures.
            }
        }
        val afterCommit = hooks.afterCommit[change.resource.name] ?: return
        val requestId = currentRequestId()
        val context = CommitContext(change.resource, origin.caller, requestId)
        val committed = change.committed()
        for (hook in afterCommit) {
            try {
                context.hook(committed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("BaseKt request $requestId: an after-commit hook of '${change.resource.name}' failed on '${committed.id}'; the write stands.", e)
            }
        }
    }

    /**
     * Lists a provided [resource]'s records for [caller]: checks [query] against what [provider] declares, narrows it
     * to the caller's read scope, and checks what comes back.
     */
    private suspend fun providedList(resource: Resource, provider: RecordProvider, caller: Identity?, query: Query): Page {
        checkProvidedQuery(resource, provider, query)
        val scope = scopeFilter(resource, Operation.READ, caller)
        if (matchesNothing(scope)) return Page(emptyList(), null)
        val narrowed = query.narrowed(scope)
        val page = provided(resource) {
            val page = provider.list(narrowed)
            check(page.items.size <= query.limit) { "The provider of '${resource.name}' returned ${page.items.size} records for a limit of ${query.limit}." }
            page.copy(items = page.items.map(resource::providedRecord))
        }
        // The provider applied the scope; a record outside it never reaches the caller all the same.
        return page.copy(items = page.items.filter { inScope(resource, Operation.READ, caller, it) })
    }

    /** Returns the record with [id] that [provider] serves for [resource], checked, or `null` if there isn't one. */
    private suspend fun providedGet(resource: Resource, provider: RecordProvider, id: String): Record? = provided(resource) {
        provider.get(id)?.let(resource::providedRecord)?.also { record ->
            check(record[Resource.ID] == Value.Text(id)) { "The provider of '${resource.name}' returned '${record[Resource.ID]}' for the id '$id'." }
        }
    }

    /**
     * Runs [block], a call of a provided [resource]'s provider. Its failures answer [ErrorCode.UNAVAILABLE], except an
     * [ApiException] it throws itself and an invalid query, such as a cursor it didn't issue.
     */
    private suspend fun <T> provided(resource: Resource, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        throw e
    } catch (e: InvalidQueryException) {
        throw ApiException(ErrorCode.INVALID_QUERY, e.message!!)
    } catch (e: Exception) {
        throw ApiException(ErrorCode.UNAVAILABLE, "The records of '${resource.name}' can't be read right now. Try again later.", e)
    }

    /** Refuses any write to a provided [resource], whose records belong to the application's code. */
    private fun refuseWrite(resource: Resource) {
        if (resource.provided) {
            throw ApiException(ErrorCode.READ_ONLY, "'${resource.name}' is read-only: its records come from the application's code.")
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
/** How many records a scan for a delete or [ActionContext.deleteWhere] reads at a time. */
private const val SCAN_PAGE = 500

private fun Query.narrowed(filter: Filter?): Query =
    if (filter == null) this else copy(filter = this.filter?.let { Filter.And(listOf(it, filter)) } ?: filter)
