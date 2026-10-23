package io.github.basekt.server

import io.github.basekt.data.DataSource
import io.github.basekt.schema.FieldError
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource

/**
 * A module's part in every write, for modules that keep something beside the records, such as `basekt-files` and
 * the files that file fields point to. Register one with [ResourceService.extend] when the module is installed.
 *
 * Unlike hooks, which belong to the application, extensions belong to modules, and they see every resource.
 */
public interface WriteExtension {
    /**
     * Checks what a create or update is about to store, after the hooks ran, inside the write's transaction.
     * [record] holds only the fields being written. Returns the fields to reject, which fail the write with
     * `validation_failed`.
     */
    public suspend fun check(resource: Resource, record: Record, transaction: DataSource): List<FieldError> = emptyList()

    /**
     * Learns of a write after its transaction committed, so it never acts on a write that was rolled back. Its
     * failures are the extension's own: they don't fail the write, which has already happened.
     */
    public suspend fun committed(change: Change) {}

    /**
     * Learns of a write inside its transaction, after the after-hooks ran. What it writes through
     * [WriteContext.transaction] commits or rolls back with the write, and a failure fails the write. Writes that
     * trusted code makes through raw data access, such as a hook's [HookContext.data], aren't reported.
     */
    public suspend fun written(change: Change, context: WriteContext) {}

    /** Learns of an action that ran, inside its transaction, after its code returned and before it commits. */
    public suspend fun ran(run: ActionRun, context: WriteContext) {}
}

/** Where a write or an action comes from, as [WriteExtension.written] and [WriteExtension.ran] see it. */
public class WriteContext internal constructor(
    /** Who made the call, or `null` for an anonymous caller. */
    public val caller: Identity?,
    /** The data source of the transaction the write is in. */
    public val transaction: DataSource,
    /**
     * An id of the call: one REST write, or one action with every write it made. Extensions use it to group what
     * happened together.
     */
    public val operation: String,
    /** The action the write is part of, or `null` for a write of its own. */
    public val action: ActionRun?,
)

/** An action that ran. */
public data class ActionRun(
    /** The resource the action belongs to. */
    val resource: Resource,
    /** The action's name. */
    val action: String,
    /** The id of the record it ran on, or `null` for an action on the whole resource. */
    val id: String?,
    /** Its checked input. */
    val input: Record,
)

/** The caller, call and action a write belongs to, passed along the write steps. */
internal class Origin(val caller: Identity?, val operation: String, val action: ActionRun? = null) {
    fun context(transaction: DataSource): WriteContext = WriteContext(caller, transaction, operation, action)

    companion object {
        /** A new call by [caller]. */
        fun of(caller: Identity?, action: ActionRun? = null): Origin = Origin(caller, java.util.UUID.randomUUID().toString(), action)
    }
}

/** A write that committed, as a [WriteExtension] learns of it. */
public sealed interface Change {
    /** The resource written. */
    public val resource: Resource

    /**
     * A record was created.
     *
     * @property record the stored record.
     */
    public data class Created(override val resource: Resource, val record: Record) : Change

    /**
     * A record was updated.
     *
     * @property before the record before the update.
     * @property after the record after it.
     */
    public data class Updated(override val resource: Resource, val before: Record, val after: Record) : Change

    /**
     * A record was deleted.
     *
     * @property before what it held.
     */
    public data class Deleted(override val resource: Resource, val before: Record) : Change
}
