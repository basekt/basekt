package io.github.basekt.server

import io.github.basekt.schema.Model
import io.github.basekt.schema.Operation
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
import kotlinx.serialization.serializer

/**
 * What an after-commit hook sees besides the change: the [resource], the [caller] and the [requestId] of the call
 * that wrote it. The transaction is over, so there is no data source of the write to use.
 */
public class CommitContext internal constructor(
    /** The resource that was written. */
    public val resource: Resource,
    /** Who wrote, or `null` for an anonymous caller. */
    public val caller: Identity?,
    /** The [requestId] of the call that wrote, or `null` for a write that no call made, such as a job's. */
    public val requestId: String?,
)

/**
 * A write that committed, as an after-commit hook sees it: a [Record], or the class of a typed model.
 *
 * @param T what the record is seen as.
 */
public sealed interface Committed<out T> {
    /** The record's id. */
    public val id: String

    /** What the write did: [Operation.CREATE], [Operation.UPDATE] or [Operation.DELETE]. */
    public val operation: Operation

    /** The record as it is now, or `null` after a delete. */
    public val record: T?

    /** The record before the write, or `null` after a create. */
    public val before: T?

    /**
     * A record was created.
     *
     * @property record the stored record.
     */
    public data class Created<out T>(override val id: String, override val record: T) : Committed<T> {
        override val operation: Operation get() = Operation.CREATE
        override val before: T? get() = null
    }

    /**
     * A record was updated.
     *
     * @property before the record before the update.
     * @property record the record after it.
     */
    public data class Updated<out T>(override val id: String, override val before: T, override val record: T) : Committed<T> {
        override val operation: Operation get() = Operation.UPDATE
    }

    /**
     * A record was deleted.
     *
     * @property before what it held.
     */
    public data class Deleted<out T>(override val id: String, override val before: T) : Committed<T> {
        override val operation: Operation get() = Operation.DELETE
        override val record: T? get() = null
    }
}

/** This change as an after-commit hook sees it. */
internal fun Change.committed(): Committed<Record> = when (this) {
    is Change.Created -> Committed.Created(record.id(), record)
    is Change.Updated -> Committed.Updated(after.id(), before, after)
    is Change.Deleted -> Committed.Deleted(before.id(), before)
}

private fun Record.id(): String = (this[Resource.ID] as Value.Text).value

private fun <A, B> Committed<A>.map(transform: (A) -> B): Committed<B> = when (this) {
    is Committed.Created -> Committed.Created(id, transform(record))
    is Committed.Updated -> Committed.Updated(id, transform(before), transform(record))
    is Committed.Deleted -> Committed.Deleted(id, transform(before))
}

/** Runs [hook] once for each change to a [T] that committed. See [HooksBuilder.afterCommit]. */
public fun <T> HooksBuilder.afterCommit(model: Model<T>, hook: suspend CommitContext.(change: Committed<T>) -> Unit) {
    afterCommit(model.name) { change -> hook(change.map(model::fromRecord)) }
}

/** Like [afterCommit] with a model, for the typed resource of class [T] declared in the schema. */
public inline fun <reified T> HooksBuilder.afterCommit(noinline hook: suspend CommitContext.(change: Committed<T>) -> Unit) {
    typed(serializer<T>()) { afterCommit(it, hook) }
}
