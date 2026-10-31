package io.github.basekt.server

import io.github.basekt.data.Repository
import io.github.basekt.schema.Model
import io.github.basekt.schema.Resource
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.reflect.KProperty1

/*
 * Typed hooks: the same hooks as HooksBuilder's, seen through a Model's class. Each one converts the records to and
 * from T and registers an ordinary hook, so they run in the same order, transaction and transports as the rest.
 */

/** Runs [hook] on each [T] about to be created; it returns the value to create, or a veto. */
public fun <T> HooksBuilder.beforeCreate(model: Model<T>, hook: suspend HookContext.(new: T) -> Verdict<T>) {
    beforeCreate(model.name) { record -> hook(model.fromRecord(record)).map(model::toRecord) }
}

/**
 * Runs [hook] on each update of a [T]: the record before the update, and as it would be after. It returns the value
 * to store, or a veto. The fields it changes are written along with the caller's.
 */
public fun <T> HooksBuilder.beforeUpdate(model: Model<T>, hook: suspend HookContext.(old: T, new: T) -> Verdict<T>) {
    beforeUpdate(model.name) { update ->
        hook(model.fromRecord(update.current), model.fromRecord(update.merged)).map { value ->
            (model.toRecord(value) - Resource.ID).filter { (field, stored) -> field in update.changes || stored != update.current[field] }
        }
    }
}

/** Runs [hook] on each [T] about to be deleted; it returns `proceed(Unit)` or a veto. */
public fun <T> HooksBuilder.beforeDelete(model: Model<T>, hook: suspend HookContext.(old: T) -> Verdict<Unit>) {
    beforeDelete(model.name) { deletion -> hook(model.fromRecord(deletion.current)) }
}

/** Runs [hook] on each [T] created. */
public fun <T> HooksBuilder.afterCreate(model: Model<T>, hook: suspend HookContext.(created: T) -> Unit) {
    afterCreate(model.name) { record -> hook(model.fromRecord(record)) }
}

/** Runs [hook] on each update of a [T], with the record before and after it. */
public fun <T> HooksBuilder.afterUpdate(model: Model<T>, hook: suspend HookContext.(old: T, new: T) -> Unit) {
    afterUpdate(model.name) { updated -> hook(model.fromRecord(updated.before), model.fromRecord(updated.after)) }
}

/** Runs [hook] on each [T] deleted. */
public fun <T> HooksBuilder.afterDelete(model: Model<T>, hook: suspend HookContext.(deleted: T) -> Unit) {
    afterDelete(model.name) { record -> hook(model.fromRecord(record)) }
}

/** Like [beforeCreate] with a model, for the typed resource of class [T] declared in the schema. */
public inline fun <reified T> HooksBuilder.beforeCreate(noinline hook: suspend HookContext.(new: T) -> Verdict<T>) {
    typed(serializer<T>()) { beforeCreate(it, hook) }
}

/** Like [beforeUpdate] with a model, for the typed resource of class [T] declared in the schema. */
public inline fun <reified T> HooksBuilder.beforeUpdate(noinline hook: suspend HookContext.(old: T, new: T) -> Verdict<T>) {
    typed(serializer<T>()) { beforeUpdate(it, hook) }
}

/** Like [beforeDelete] with a model, for the typed resource of class [T] declared in the schema. */
public inline fun <reified T> HooksBuilder.beforeDelete(noinline hook: suspend HookContext.(old: T) -> Verdict<Unit>) {
    typed(serializer<T>()) { beforeDelete(it, hook) }
}

/** Like [afterCreate] with a model, for the typed resource of class [T] declared in the schema. */
public inline fun <reified T> HooksBuilder.afterCreate(noinline hook: suspend HookContext.(created: T) -> Unit) {
    typed(serializer<T>()) { afterCreate(it, hook) }
}

/** Like [afterUpdate] with a model, for the typed resource of class [T] declared in the schema. */
public inline fun <reified T> HooksBuilder.afterUpdate(noinline hook: suspend HookContext.(old: T, new: T) -> Unit) {
    typed(serializer<T>()) { afterUpdate(it, hook) }
}

/** Like [afterDelete] with a model, for the typed resource of class [T] declared in the schema. */
public inline fun <reified T> HooksBuilder.afterDelete(noinline hook: suspend HookContext.(deleted: T) -> Unit) {
    typed(serializer<T>()) { afterDelete(it, hook) }
}

/** Returns a [Repository] of [model]'s records in this hook's transaction. */
public fun <T> HookContext.repository(model: Model<T>): Repository<T> = Repository(model, data)

/** Refuses the write because of [property]'s value, explained by [message]. */
public fun HookContext.veto(property: KProperty1<*, *>, message: String): Verdict<Nothing> = veto(property.name, message)

/** Returns a [Repository] of [model]'s records in BaseKt's data source. See [Repository] for what it skips. */
public fun <T> BaseKtRuntime.repository(model: Model<T>): Repository<T> = Repository(model, dataSource)

/** Returns a [Repository] of the typed resource of class [T] declared in the schema. */
public inline fun <reified T> BaseKtRuntime.repository(): Repository<T> = repository(modelOf(serializer<T>(), models))

/** Returns the model in [models] whose class [serializer] serializes, or throws [IllegalStateException]. */
@PublishedApi
internal fun <T> modelOf(serializer: KSerializer<T>, models: List<Model<*>>): Model<T> {
    val name = serializer.descriptor.serialName
    @Suppress("UNCHECKED_CAST")
    return checkNotNull(models.firstOrNull { it.serializer.descriptor.serialName == name } as Model<T>?) {
        "No typed resource is declared for $name. Declare it with resource<${name.substringAfterLast('.')}>(\"name\") { } in the schema."
    }
}

private fun <A, B> Verdict<A>.map(transform: (A) -> B): Verdict<B> = when (this) {
    is Verdict.Proceed -> Verdict.Proceed(transform(value))
    is Verdict.Veto -> this
}
