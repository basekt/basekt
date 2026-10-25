package io.github.basekt.openapi

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Adds [other]'s routes and components to this document, for the routes an application serves next to BaseKt's.
 *
 * - `paths`: a path only [other] has is added; on a path both have, each operation is added, and the same method on
 *   the same path in both is a conflict.
 * - `components`: each entry is added, and a name both have with different content is a conflict.
 * - `tags`: [other]'s are added after this document's, without repeats.
 *
 * Everything else, such as `info`, `servers` and `security`, stays this document's. Throws
 * [IllegalArgumentException] naming every conflict.
 */
public fun JsonObject.mergeOpenApi(other: JsonObject): JsonObject {
    val conflicts = mutableListOf<String>()
    val paths = LinkedHashMap(objectAt("paths"))
    for ((path, item) in other.objectAt("paths")) {
        val mine = paths[path] as? JsonObject
        if (mine == null) {
            paths[path] = item
            continue
        }
        val merged = LinkedHashMap(mine)
        for ((key, value) in item as? JsonObject ?: JsonObject(emptyMap())) {
            val existing = merged[key]
            when {
                existing == null -> merged[key] = value
                existing != value -> conflicts += "Both documents define ${if (key in methods) "${key.uppercase()} $path" else "'$key' of $path"}."
            }
        }
        paths[path] = JsonObject(merged)
    }
    val components = LinkedHashMap(objectAt("components"))
    for ((section, entries) in other.objectAt("components")) {
        val merged = LinkedHashMap(components[section] as? JsonObject ?: JsonObject(emptyMap()))
        for ((name, value) in entries as? JsonObject ?: JsonObject(emptyMap())) {
            val existing = merged[name]
            when {
                existing == null -> merged[name] = value
                existing != value -> conflicts += "Both documents define components.$section.$name, differently."
            }
        }
        components[section] = JsonObject(merged)
    }
    require(conflicts.isEmpty()) { "The OpenAPI documents can't be merged:\n" + conflicts.joinToString("\n") { "- $it" } }
    val tags = (arrayAt("tags") + other.arrayAt("tags")).distinctBy { (it as? JsonObject)?.get("name")?.jsonPrimitive?.content ?: it }
    return JsonObject(
        LinkedHashMap<String, JsonElement>(this).apply {
            if (tags.isNotEmpty()) put("tags", JsonArray(tags))
            put("paths", JsonObject(paths))
            put("components", JsonObject(components))
        },
    )
}

private val methods = setOf("get", "put", "post", "delete", "options", "head", "patch", "trace")

private fun JsonObject.objectAt(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())

private fun JsonObject.arrayAt(key: String): List<JsonElement> = this[key] as? JsonArray ?: emptyList()
