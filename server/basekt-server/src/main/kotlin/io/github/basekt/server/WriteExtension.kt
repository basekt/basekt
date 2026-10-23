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
