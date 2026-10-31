package io.github.basekt.server

import io.github.basekt.data.Filter
import io.github.basekt.data.FilterScope
import io.github.basekt.data.Repository
import io.github.basekt.schema.ActionInput
import io.github.basekt.schema.Auto
import io.github.basekt.schema.Model
import io.github.basekt.schema.Resource
import io.github.basekt.schema.actionInput
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import kotlin.reflect.KProperty1

/*
 * Typed actions: the same actions as ActionsBuilder's, seen through a Model's class and an ActionInput's class. Each
 * one decodes the target record and input, runs the handler, and encodes a @Serializable answer object (or returns
 * null when the handler returns Unit or null, leaving the default response in place).
 */

/** Reads the target record of this record action as [model]'s [T]. */
public fun <T> ActionContext.record(model: Model<T>): T {
    val raw = checkNotNull(record) { "Action on '${resource.name}' has no target record." }
    return model.fromRecord(raw)
}

/**
 * Creates [value] in [model]'s resource through this action's service pipeline (validation, automatic fields, hooks
 * and extensions) and returns the stored record as [T]. Automatic fields on [value] are ignored so the server stamps
 * them.
 */
public suspend fun <T> ActionContext.create(model: Model<T>, value: T): T {
    val autoFields = model.resource.fields.filter { it.auto != null }.mapTo(hashSetOf()) { it.name }
    val prepared = model.toRecord(value).filterKeys { it !in autoFields }
    return model.fromRecord(create(model.resource, prepared))
}

/**
 * Updates the record of [model] with [id] to [value] through this action's service pipeline and returns the stored
 * record as [T]. Automatic fields other than a non-null [Auto.Version] are left untouched.
 */
public suspend fun <T> ActionContext.update(model: Model<T>, id: String, value: T): T {
    val raw = model.toRecord(value)
    val autoSkip = model.resource.fields
        .filter { it.auto != null && !(it.auto == Auto.Version && raw[it.name] != null) }
        .mapTo(hashSetOf()) { it.name }
    val changes = (raw - Resource.ID).filterKeys { it !in autoSkip }
    return model.fromRecord(update(model.resource, id, changes))
}

/** Deletes the record of [model] with [id] through this action's service pipeline, including its cascades. */
public suspend fun ActionContext.delete(model: Model<*>, id: String) {
    delete(model.resource, id)
}

/** Deletes every record of [model] matching [where] through this action's service pipeline and returns the count. */
public suspend fun <T> ActionContext.deleteWhere(model: Model<T>, where: FilterScope<T>.() -> Filter): Int {
    val filter = checkNotNull(Repository(model, data).query { where(where) }.filter)
    return deleteWhere(model.resource, filter)
}

/** Returns a [Repository] of [model]'s records in this action's transaction. */
public fun <T> ActionContext.repository(model: Model<T>): Repository<T> = Repository(model, data)

/** Stops the action and rolls back what it wrote because of input [property], explained by [message]. */
public fun ActionContext.fail(property: KProperty1<*, *>, message: String): Nothing = fail(property.name, message)

/**
 * Runs [handler] on [model]'s record action [name], decoding the target record as [T] and the caller's input through
 * [input]. Returning `Unit` or `null` answers with the updated record; returning a `@Serializable` object answers with
 * its JSON.
 */
public inline fun <T, I, reified O> ActionsBuilder.on(
    model: Model<T>,
    name: String,
    input: ActionInput<I>,
    noinline handler: suspend ActionContext.(record: T, input: I) -> O,
) {
    val outSerializer = answerSerializer<O>()
    on(model.name, name) { rawInput ->
        val out = handler(record(model), input.fromRecord(rawInput))
        encodeActionAnswer(outSerializer, out)
    }
}

/** Runs [handler] on [model]'s record action [name], decoding the caller's input as [I]. */
public inline fun <T, reified I, reified O> ActionsBuilder.on(
    model: Model<T>,
    name: String,
    noinline handler: suspend ActionContext.(record: T, input: I) -> O,
) {
    on(model, name, actionInput<I>(), handler)
}

/** Like [on] with a model, for the typed resource of class [T] declared in the schema. */
public inline fun <reified T, reified I, reified O> ActionsBuilder.on(
    name: String,
    noinline handler: suspend ActionContext.(record: T, input: I) -> O,
) {
    val input = actionInput<I>()
    val outSerializer = answerSerializer<O>()
    typed(serializer<T>()) { model ->
        on(model.name, name) { rawInput ->
            val out = handler(record(model), input.fromRecord(rawInput))
            encodeActionAnswer(outSerializer, out)
        }
    }
}

/** Runs [handler] on [model]'s record action [name] when the action takes no input fields. */
public inline fun <T, reified O> ActionsBuilder.onRecord(
    model: Model<T>,
    name: String,
    noinline handler: suspend ActionContext.(record: T) -> O,
) {
    val outSerializer = answerSerializer<O>()
    on(model.name, name) {
        encodeActionAnswer(outSerializer, handler(record(model)))
    }
}

/** Like [onRecord] with a model, for the typed resource of class [T] declared in the schema. */
public inline fun <reified T, reified O> ActionsBuilder.onRecord(
    name: String,
    noinline handler: suspend ActionContext.(record: T) -> O,
) {
    val outSerializer = answerSerializer<O>()
    typed(serializer<T>()) { model ->
        on(model.name, name) {
            encodeActionAnswer(outSerializer, handler(record(model)))
        }
    }
}

/** Runs [handler] on [resource]'s resource-wide action [name], decoding the caller's input through [input]. */
public inline fun <I, reified O> ActionsBuilder.onResource(
    resource: String,
    name: String,
    input: ActionInput<I>,
    noinline handler: suspend ActionContext.(input: I) -> O,
) {
    val outSerializer = answerSerializer<O>()
    on(resource, name) { rawInput ->
        encodeActionAnswer(outSerializer, handler(input.fromRecord(rawInput)))
    }
}

/** Runs [handler] on [resource]'s resource-wide action [name], decoding the caller's input as [I]. */
public inline fun <reified I, reified O> ActionsBuilder.onResource(
    resource: String,
    name: String,
    noinline handler: suspend ActionContext.(input: I) -> O,
) {
    onResource(resource, name, actionInput<I>(), handler)
}

/** Runs [handler] on [model]'s resource-wide action [name], decoding the caller's input as [I]. */
public inline fun <reified I, reified O> ActionsBuilder.onResource(
    model: Model<*>,
    name: String,
    noinline handler: suspend ActionContext.(input: I) -> O,
) {
    onResource(model.name, name, actionInput<I>(), handler)
}

@PublishedApi
internal inline fun <reified O> answerSerializer(): KSerializer<O>? =
    if (O::class == Unit::class) null else serializer<O>()

@PublishedApi
internal fun <O> encodeActionAnswer(serializer: KSerializer<O>?, value: O): JsonObject? {
    if (value == null || value is Unit || serializer == null) return null
    val encoded = ActionAnswerJson.encodeToJsonElement(serializer, value)
    return encoded as? JsonObject
        ?: error("Typed action answer ${serializer.descriptor.serialName} must serialize to a JSON object.")
}

private val ActionAnswerJson = Json {
    encodeDefaults = true
    explicitNulls = true
}
