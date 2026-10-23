package io.github.basekt.data

import io.github.basekt.schema.Field
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
import io.github.basekt.schema.decode
import io.github.basekt.schema.toJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64

/**
 * The position of a record in a sorted list: its [values] for each sort key, then its [id]. Keyset-paginating
 * adapters return the records strictly after it.
 */
public data class CursorKey(
    /** The record's value for each [Sort] key, in order. */
    val values: List<Value?>,
    /** The record's id, the final tie-breaker. */
    val id: String,
)

/**
 * Turns [CursorKey]s into opaque cursor strings and back, for adapters that paginate by keyset. A cursor remembers
 * its sort, so it can't be reused with another one.
 */
public class CursorCodec(
    /** The resource being listed. */
    private val resource: Resource,
    /** The query's sort keys. */
    private val sort: List<Sort>,
) {
    private val signature = sort.joinToString(",") { "${it.field}:${it.direction}" }

    /** Returns the key of [record] under this sort. */
    public fun keyOf(record: Record): CursorKey =
        CursorKey(sort.map { record[it.field] }, (record[Resource.ID] as Value.Text).value)

    /** Encodes [key] as an opaque cursor. */
    public fun encode(key: CursorKey): String {
        val json = JsonObject(
            mapOf(
                "s" to JsonPrimitive(signature),
                "k" to JsonArray(key.values.map { it?.toJson() ?: JsonNull }),
                "i" to JsonPrimitive(key.id),
            ),
        )
        return Base64.UrlSafe.encode(json.toString().encodeToByteArray())
    }

    /** Decodes [cursor], or throws [InvalidQueryException] if it is malformed or was issued for another sort. */
    public fun decode(cursor: String): CursorKey {
        val json = runCatching {
            Json.parseToJsonElement(Base64.UrlSafe.decode(cursor).decodeToString()).jsonObject
        }.getOrElse { throw InvalidQueryException("The cursor is malformed.") }
        return runCatching {
            require(json.getValue("s").jsonPrimitive.content == signature)
            val raw = json.getValue("k").jsonArray
            require(raw.size == sort.size)
            val values = sort.zip(raw).map { (key, element) ->
                if (element is JsonNull) null
                else requireNotNull(Field(key.field, resource.fieldType(key.field)).decode(element))
            }
            CursorKey(values, json.getValue("i").jsonPrimitive.content)
        }.getOrElse { throw InvalidQueryException("The cursor doesn't belong to this query's sort.") }
    }
}
