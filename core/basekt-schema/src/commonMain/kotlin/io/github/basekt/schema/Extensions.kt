package io.github.basekt.schema

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer

/**
 * A resource builder that other modules can attach settings to, so a module can add its own block to the DSL
 * without the schema knowing what it means.
 *
 * A module writes an extension function on this type that stores its settings under a key of its own, and reads them
 * back with [Resource.extension]. The schema keeps them as JSON, checks only the key, and carries them through its
 * JSON form. Keys belong to their module: prefix them (`storefront`, `payments.methods`) so two modules don't clash.
 */
public interface Extensible {
    /** Keeps [value] under [key] on the resource. Keeping another under the same key replaces it. */
    public fun extend(key: String, value: JsonElement)
}

/** Keeps [value], encoded with [serializer], under [key] on the resource. */
public fun <T> Extensible.extension(key: String, serializer: KSerializer<T>, value: T) {
    extend(key, SchemaJson.encodeToJsonElement(serializer, value))
}

/** Keeps [value] under [key] on the resource. */
public inline fun <reified T> Extensible.extension(key: String, value: T): Unit = extension(key, serializer<T>(), value)
