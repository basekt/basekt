package io.github.basekt.data

import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource

/**
 * Where a schema's records live. Every adapter (in memory, Exposed, Mongo...) implements this interface and passes
 * the contract suite in `testing:basekt-testing`.
 *
 * The contract:
 * - Records returned hold [Resource.ID] and every field of the resource, with `null` for a field that isn't set.
 * - Adapters store the values they're given and don't validate them against field rules; the caller does.
 * - [list] follows [Query]'s semantics for filters, sorting, nulls and cursors.
 * - Every function is safe to call concurrently.
 */
public interface DataSource {
    /** Returns the record of [resource] with [id], or `null` if there isn't one. */
    public suspend fun get(resource: Resource, id: String): Record?

    /**
     * Returns one page of [resource]'s records that match [query]. Throws [InvalidQueryException] if the query names
     * an unknown field, compares a field with a value of another type, or has a cursor this source didn't issue for
     * the same sort.
     */
    public suspend fun list(resource: Resource, query: Query = Query()): Page

    /**
     * Stores a new record of [resource] and returns it. If [record] has no [Resource.ID], the source generates one.
     * Throws [DuplicateIdException] if the id is taken.
     */
    public suspend fun create(resource: Resource, record: Record): Record

    /**
     * Applies [changes] to the record of [resource] with [id] and returns the updated record, or `null` if there
     * isn't one. Fields absent from [changes] keep their value; a `null` value clears the field.
     */
    public suspend fun update(resource: Resource, id: String, changes: Record): Record?

    /** Deletes the record of [resource] with [id]. Returns `false` if there wasn't one. */
    public suspend fun delete(resource: Resource, id: String): Boolean

    /**
     * Runs [block] in a transaction and returns its result. Calls on the [DataSource] passed to [block] see the
     * transaction's own writes; they're committed when [block] returns and rolled back if it throws. Transactions
     * may nest: a failed inner one rolls back only its own writes.
     *
     * Use only the source passed to [block] inside it; calling the outer source may wait for the transaction.
     */
    public suspend fun <T> transaction(block: suspend (DataSource) -> T): T
}

/** A data source refused an operation. */
public open class DataException(message: String) : RuntimeException(message)

/** A create used an id that is already taken. */
public class DuplicateIdException(
    /** The resource's name. */
    public val resource: String,
    /** The taken id. */
    public val id: String,
) : DataException("'$resource' already has a record with id '$id'.")

/** A [Query] doesn't fit the resource, or its cursor is invalid. */
public class InvalidQueryException(message: String) : DataException(message)
