package io.github.basekt.server

import io.github.basekt.data.DataSource
import io.github.basekt.data.Filter
import io.github.basekt.schema.FieldError
import io.github.basekt.schema.FieldErrorCode
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import kotlinx.serialization.json.JsonObject

/**
 * The code that runs an [io.github.basekt.schema.Action]. It gets the action's checked [input] and returns the JSON
 * to answer with, or `null` for the default: the target record as it is after the action, or no content for an
 * action on the whole resource.
 */
public typealias ActionHandler = suspend ActionContext.(input: Record) -> JsonObject?

/**
 * What an action's code sees and may do: the [resource], the [caller], the target [record] (for an action on one
 * record), the checked [input], and the [data] source of the action's transaction.
 *
 * Everything an action writes is in that one transaction: it commits when the handler returns and rolls back if it
 * throws or [fail]s. Write through [create], [update] and [delete] to go through the same steps as the REST API, with
 * automatic fields, validation, hooks, versions and extensions, but without the caller's permissions and scopes: the
 * action's own permission covers what it writes. [data] is raw access, for reads and
 * [io.github.basekt.data.ConditionalWrites].
 */
public class ActionContext internal constructor(
    /** The resource the action belongs to. */
    public val resource: Resource,
    /** Who runs the action, or `null` for an anonymous caller. */
    public val caller: Identity?,
    /** The record the action runs on, or `null` for an action on the whole resource. */
    public val record: Record?,
    /** The caller's input, decoded and checked against the action's input fields. */
    public val input: Record,
    /** The data source of the action's transaction. */
    public val data: DataSource,
    private val service: ResourceService,
    private val changes: MutableList<Change>,
    private val origin: Origin,
) {
    /** Creates a record of [resource] from [record], with the same steps as a create through the API. */
    public suspend fun create(resource: Resource, record: Record): Record =
        service.trustedCreate(data, resource, origin, record).also { changes += Change.Created(resource, it) }

    /** Applies [changes] to the record of [resource] with [id], with the same steps as an update through the API. */
    public suspend fun update(resource: Resource, id: String, changes: Record): Record {
        val (before, after) = service.trustedUpdate(data, resource, origin, id, changes)
        this.changes += Change.Updated(resource, before, after)
        return after
    }

    /**
     * Deletes the record of [resource] with [id], with the same steps as a delete through the API, including what
     * the [io.github.basekt.schema.OnDelete] rules of the records that belong to it call for.
     */
    public suspend fun delete(resource: Resource, id: String) {
        changes += service.trustedDelete(data, resource, origin, id)
    }

    /**
     * Deletes every record of [resource] that matches [filter], each like [delete], and returns how many it deleted.
     * A record that an earlier one's cascade already removed isn't counted. One action can erase what a caller owns
     * across many resources this way, such as every record whose `createdBy` is theirs, in one transaction.
     */
    public suspend fun deleteWhere(resource: Resource, filter: Filter): Int {
        var deleted = 0
        for (id in service.idsWhere(data, resource, filter)) {
            if (data.get(resource, id) == null) continue
            changes += service.trustedDelete(data, resource, origin, id)
            deleted++
        }
        return deleted
    }

    /** Stops the action and rolls back what it wrote, refusing it with [reason]. */
    public fun fail(reason: String): Nothing = throw ApiException(ErrorCode.REJECTED, reason)

    /** Stops the action and rolls back what it wrote, because of the input [field], explained by [message]. */
    public fun fail(field: String, message: String): Nothing =
        throw ApiException(ApiError(ErrorCode.VALIDATION_FAILED, "The input has invalid fields.", listOf(FieldError(field, FieldErrorCode.REJECTED, message))))
}

/** The code of every action, by resource and action name. Build it with [ActionsBuilder]. */
public class Actions internal constructor(
    internal val handlers: Map<String, Map<String, ActionHandler>>,
) {
    /** Returns the code of [resource]'s action [name], or `null` if none is registered. */
    public fun handler(resource: String, name: String): ActionHandler? = handlers[resource]?.get(name)

    /** No actions. */
    public companion object {
        /** No action code at all. */
        public val None: Actions = Actions(emptyMap())
    }
}

/** Registers the code of the schema's actions. */
public class ActionsBuilder {
    private val handlers = linkedMapOf<String, MutableMap<String, ActionHandler>>()

    /** Runs [handler] when a caller runs [resource]'s action [name]. */
    public fun on(resource: String, name: String, handler: ActionHandler) {
        val registered = handlers.getOrPut(resource) { linkedMapOf() }
        require(name !in registered) { "The action '$resource.$name' has code registered twice." }
        registered[name] = handler
    }

    /** Returns the code registered so far. */
    public fun build(): Actions = Actions(handlers.mapValues { it.value.toMap() })
}
