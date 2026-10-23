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
import io.github.basekt.schema.Value
import io.github.basekt.schema.WriteMode
import io.github.basekt.schema.decodeRecord
import kotlinx.serialization.json.JsonObject

/**
 * The operations behind the REST routes, with no Ktor types: permission checks, validation, referential integrity
 * and calls to the [dataSource]. Other modules, such as an admin API, reuse it.
 *
 * Every function throws [ApiException] when the request can't be served.
 */
public class ResourceService(
    /** The schema whose resources are served. */
    public val schema: Schema,
    /** Where the records live. */
    public val dataSource: DataSource,
    /** The validators that [io.github.basekt.schema.Rule.Custom] rules name. */
    private val validators: Map<String, FieldValidator> = emptyMap(),
) {
    /** Returns the resource called [name], or throws [ErrorCode.NOT_FOUND]. */
    public fun resource(name: String): Resource =
        schema.resource(name) ?: throw ApiException(ErrorCode.NOT_FOUND, "There is no resource '$name'.")

    /** Lists records of [resource] that match [query], if [caller] may read them. */
    public suspend fun list(resource: Resource, caller: Identity?, query: Query): Page {
        authorize(resource, Operation.READ, caller)
        return queried { dataSource.list(resource, query) }
    }

    /** Returns the record of [resource] with [id], if [caller] may read it. */
    public suspend fun get(resource: Resource, caller: Identity?, id: String): Record {
        authorize(resource, Operation.READ, caller)
        return dataSource.get(resource, id) ?: throw notFound(resource, id)
    }

    /** Validates [body] and creates a record of [resource] from it, if [caller] may. */
    public suspend fun create(resource: Resource, caller: Identity?, body: JsonObject): Record {
        authorize(resource, Operation.CREATE, caller)
        val record = decode(resource, body, WriteMode.CREATE)
        return dataSource.transaction { tx ->
            checkReferences(tx, resource, record)
            try {
                tx.create(resource, record)
            } catch (e: DuplicateIdException) {
                throw ApiException(ErrorCode.CONFLICT, e.message!!)
            }
        }
    }

    /** Validates [body] and applies it to the record of [resource] with [id], if [caller] may. */
    public suspend fun update(resource: Resource, caller: Identity?, id: String, body: JsonObject): Record {
        authorize(resource, Operation.UPDATE, caller)
        val changes = decode(resource, body, WriteMode.UPDATE)
        return dataSource.transaction { tx ->
            checkReferences(tx, resource, changes)
            tx.update(resource, id, changes) ?: throw notFound(resource, id)
        }
    }

    /** Deletes the record of [resource] with [id], if [caller] may. */
    public suspend fun delete(resource: Resource, caller: Identity?, id: String) {
        authorize(resource, Operation.DELETE, caller)
        if (!dataSource.delete(resource, id)) throw notFound(resource, id)
    }

    /** Returns the one record that the record [id] of [resource] belongs to through [relation]. */
    public suspend fun parent(resource: Resource, caller: Identity?, id: String, relation: Relation.BelongsTo): Record {
        val target = resource(relation.target)
        authorize(target, Operation.READ, caller)
        val record = get(resource, caller, id)
        val parentId = (record[relation.field] as Value.Text?)?.value
            ?: throw ApiException(ErrorCode.NOT_FOUND, "'${resource.name}/$id' has no '${relation.name}'.")
        return dataSource.get(target, parentId) ?: throw notFound(target, parentId)
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
        val filter = query.filter?.let { Filter.And(listOf(link, it)) } ?: link
        return queried { dataSource.list(target, query.copy(filter = filter)) }
    }

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

    private fun invalid(errors: List<FieldError>) =
        ApiException(ApiError(ErrorCode.VALIDATION_FAILED, "The record has invalid fields.", errors))

    private fun notFound(resource: Resource, id: String) =
        ApiException(ErrorCode.NOT_FOUND, "There is no '${resource.name}' with id '$id'.")
}
