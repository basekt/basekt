package io.github.basekt.server

import io.github.basekt.data.DataSource
import io.github.basekt.schema.FieldError
import io.github.basekt.schema.FieldErrorCode
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource

/**
 * What a hook sees besides the record: the [resource], the [caller], and the [data] source of the write's
 * transaction. Reads and writes through [data] commit or roll back with the write.
 */
public class HookContext internal constructor(
    /** The resource being written. */
    public val resource: Resource,
    /** Who is writing, or `null` for an anonymous caller. */
    public val caller: Identity?,
    /** The data source of the write's transaction. */
    public val data: DataSource,
) {
    /** Lets the write go ahead with [value], which may differ from what the hook received. */
    public fun <T> proceed(value: T): Verdict<T> = Verdict.Proceed(value)

    /** Refuses the write with [reason]. */
    public fun veto(reason: String): Verdict<Nothing> = Verdict.Veto(reason)

    /** Refuses the write because of [field]'s value, explained by [message]. */
    public fun veto(field: String, message: String): Verdict<Nothing> =
        Verdict.Veto("The record has invalid fields.", listOf(FieldError(field, FieldErrorCode.REJECTED, message)))
}

/** What a before-hook decides. */
public sealed interface Verdict<out T> {
    /** The write goes ahead with [value]. */
    public data class Proceed<T>(
        /** The record or changes to write, possibly modified by the hook. */
        val value: T,
    ) : Verdict<T>

    /**
     * The write is refused. With [fields], the caller gets [ErrorCode.VALIDATION_FAILED] listing them; without,
     * [ErrorCode.REJECTED] with [reason].
     */
    public data class Veto(
        /** Why, in a sentence. */
        val reason: String,
        /** The fields that caused it, if any. */
        val fields: List<FieldError> = emptyList(),
    ) : Verdict<Nothing>
}

/** An update about to be written: the record's [current] state and the [changes] to apply. */
public data class Update(
    /** The record's id. */
    val id: String,
    /** The record as it is now. */
    val current: Record,
    /** The fields to change; absent fields keep their value. */
    val changes: Record,
) {
    /** The record as it will be after the update. */
    val merged: Record get() = current + changes
}

/** A delete about to happen: the record as it is now. */
public data class Deletion(
    /** The record's id. */
    val id: String,
    /** The record. */
    val current: Record,
)

/** A finished update: the record [before] and [after] it. */
public data class Updated(
    /** The record before the update. */
    val before: Record,
    /** The record after it. */
    val after: Record,
)

/**
 * Code that runs around writes, per resource, in [ResourceService], so every transport (REST, admin, GraphQL...)
 * runs it the same way. Declare hooks with [HooksBuilder] in `install(BaseKt) { hooks { } }`.
 *
 * - Before-hooks run in the write's transaction, after the caller's permission and the input's validation are
 *   checked. Each may modify the input it passes on, or veto the write. Modified input is validated again.
 * - After-hooks run in the same transaction once the write is done. If one throws, the write is rolled back.
 * - Several hooks on the same resource and moment run in the order they were declared.
 */
public class Hooks internal constructor(
    internal val beforeCreate: Map<String, List<suspend HookContext.(Record) -> Verdict<Record>>>,
    internal val afterCreate: Map<String, List<suspend HookContext.(Record) -> Unit>>,
    internal val beforeUpdate: Map<String, List<suspend HookContext.(Update) -> Verdict<Record>>>,
    internal val afterUpdate: Map<String, List<suspend HookContext.(Updated) -> Unit>>,
    internal val beforeDelete: Map<String, List<suspend HookContext.(Deletion) -> Verdict<Unit>>>,
    internal val afterDelete: Map<String, List<suspend HookContext.(Record) -> Unit>>,
) {
    /** The resources that hooks name. */
    internal val resources: Set<String> =
        beforeCreate.keys + afterCreate.keys + beforeUpdate.keys + afterUpdate.keys + beforeDelete.keys + afterDelete.keys

    /** No hooks. */
    public companion object {
        /** No hooks at all. */
        public val None: Hooks = HooksBuilder().build()
    }
}

/** Declares [Hooks], by resource name. */
public class HooksBuilder {
    private val beforeCreate = mutableMapOf<String, MutableList<suspend HookContext.(Record) -> Verdict<Record>>>()
    private val afterCreate = mutableMapOf<String, MutableList<suspend HookContext.(Record) -> Unit>>()
    private val beforeUpdate = mutableMapOf<String, MutableList<suspend HookContext.(Update) -> Verdict<Record>>>()
    private val afterUpdate = mutableMapOf<String, MutableList<suspend HookContext.(Updated) -> Unit>>()
    private val beforeDelete = mutableMapOf<String, MutableList<suspend HookContext.(Deletion) -> Verdict<Unit>>>()
    private val afterDelete = mutableMapOf<String, MutableList<suspend HookContext.(Record) -> Unit>>()

    /** Runs [hook] on each record about to be created in [resource]; it returns the record to create, or a veto. */
    public fun beforeCreate(resource: String, hook: suspend HookContext.(record: Record) -> Verdict<Record>) {
        beforeCreate.getOrPut(resource) { mutableListOf() } += hook
    }

    /** Runs [hook] on each record created in [resource]. */
    public fun afterCreate(resource: String, hook: suspend HookContext.(created: Record) -> Unit) {
        afterCreate.getOrPut(resource) { mutableListOf() } += hook
    }

    /** Runs [hook] on each update about to be made in [resource]; it returns the changes to apply, or a veto. */
    public fun beforeUpdate(resource: String, hook: suspend HookContext.(update: Update) -> Verdict<Record>) {
        beforeUpdate.getOrPut(resource) { mutableListOf() } += hook
    }

    /** Runs [hook] on each update made in [resource]. */
    public fun afterUpdate(resource: String, hook: suspend HookContext.(updated: Updated) -> Unit) {
        afterUpdate.getOrPut(resource) { mutableListOf() } += hook
    }

    /** Runs [hook] on each record about to be deleted from [resource]; it returns `proceed(Unit)` or a veto. */
    public fun beforeDelete(resource: String, hook: suspend HookContext.(deletion: Deletion) -> Verdict<Unit>) {
        beforeDelete.getOrPut(resource) { mutableListOf() } += hook
    }

    /** Runs [hook] on each record deleted from [resource]. */
    public fun afterDelete(resource: String, hook: suspend HookContext.(deleted: Record) -> Unit) {
        afterDelete.getOrPut(resource) { mutableListOf() } += hook
    }

    /** Returns the hooks declared so far. */
    public fun build(): Hooks = Hooks(
        beforeCreate.mapValues { it.value.toList() },
        afterCreate.mapValues { it.value.toList() },
        beforeUpdate.mapValues { it.value.toList() },
        afterUpdate.mapValues { it.value.toList() },
        beforeDelete.mapValues { it.value.toList() },
        afterDelete.mapValues { it.value.toList() },
    )
}
