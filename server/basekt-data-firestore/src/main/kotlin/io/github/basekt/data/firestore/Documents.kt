package io.github.basekt.data.firestore

import com.google.cloud.Timestamp
import com.google.cloud.firestore.CollectionReference
import com.google.cloud.firestore.DocumentReference
import io.github.basekt.schema.FieldType
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
import java.math.BigDecimal
import kotlin.time.Instant

/** A document's fields as Firestore holds them. */
internal typealias Data = Map<String, Any?>

/** How one resource's records become documents and back. */
internal class DocumentMapping(val resource: Resource, val collection: CollectionReference, private val decimals: DecimalStorage) {
    /** The type of [field], counting the id as text. */
    fun typeOf(field: String): FieldType = if (field == Resource.ID) FieldType.STRING else resource.field(field)!!.type

    /** Whether Firestore orders and compares [field]'s stored values as BaseKt orders its values. */
    fun native(field: String): Boolean = typeOf(field) != FieldType.DECIMAL || decimals == DecimalStorage.DOUBLE

    /** Whether the schema says every record has [field]. */
    fun required(field: String): Boolean = field == Resource.ID || resource.field(field)?.required == true

    /** The document of [id], or `null` if [id] can't be a Firestore document id. */
    fun ref(id: String): DocumentReference? = if (validDocumentId(id)) collection.document(id) else null

    /** Encodes [value] for [field], or for the id as a document reference. */
    fun encode(field: String, value: Value): Any = when {
        field == Resource.ID -> collection.document((value as Value.Text).value)
        else -> when (value) {
            is Value.Text -> value.value
            is Value.Integer -> value.value
            is Value.Decimal -> if (decimals == DecimalStorage.STRING) value.literal else BigDecimal(value.literal).toDouble()
            is Value.Bool -> value.value
            is Value.Timestamp -> Timestamp.ofTimeSecondsAndNanos(value.value.epochSeconds, value.value.nanosecondsOfSecond)
            is Value.Items -> throw IllegalArgumentException("A list can't be stored in '${resource.name}.$field': only an action's input may hold one.")
        }
    }

    /** Encodes an optional value: `null` is stored as a Firestore null. */
    fun encodeOrNull(field: String, value: Value?): Any? = value?.let { encode(field, it) }

    /** The data of [record]: every field, under its name. */
    fun toData(record: Record): Data {
        val data = LinkedHashMap<String, Any?>(resource.fields.size)
        for (field in resource.fields) data[field.name] = encodeOrNull(field.name, record[field.name])
        return data
    }

    /** [data] with [changes] written over it. Fields the schema doesn't name are kept. */
    fun patched(data: Data, changes: Record): Data {
        val patched = LinkedHashMap(data)
        for (field in resource.fields) if (field.name in changes) patched[field.name] = encodeOrNull(field.name, changes[field.name])
        return patched
    }

    /** The record of the document [id] holding [data]: every field of the resource, `null` for what it doesn't hold. */
    fun toRecord(id: String, data: Data): Record {
        val record = LinkedHashMap<String, Value?>(resource.fields.size + 1)
        record[Resource.ID] = Value.Text(id)
        for (field in resource.fields) record[field.name] = decode(field.type, data[field.name])
        return record
    }

    /** Reads [raw] as a value of [type]: what a field of that type holds. A number may be an integer or a double. */
    fun decode(type: FieldType, raw: Any?): Value? {
        if (raw == null) return null
        return when (type) {
            FieldType.STRING, FieldType.ENUM, FieldType.REFERENCE, FieldType.FILE -> Value.Text(if (raw is DocumentReference) raw.id else raw.toString())
            FieldType.INT, FieldType.LONG -> (raw as? Number)?.let { Value.Integer(it.toLong()) }
            FieldType.DECIMAL -> when (raw) {
                is String -> runCatching { Value.Decimal.of(BigDecimal(raw).stripTrailingZeros().toPlainString()) }.getOrNull()
                is Long, is Int -> Value.Decimal.of(raw.toString())
                is Number -> raw.toDouble().takeIf { it.isFinite() }?.let { Value.Decimal.of(BigDecimal.valueOf(it).stripTrailingZeros().toPlainString()) }
                else -> null
            }
            FieldType.BOOL -> (raw as? Boolean)?.let { Value.Bool(it) }
            FieldType.INSTANT -> (raw as? Timestamp)?.let { Value.Timestamp(Instant.fromEpochSeconds(it.seconds, it.nanos)) }
        }
    }
}

/** Whether [id] can name a document: not empty, no slash, not `.` or `..`, not `__like this__`, and at most 1500 bytes. */
internal fun validDocumentId(id: String): Boolean =
    id.isNotEmpty() && '/' !in id && id != "." && id != ".." && !(id.startsWith("__") && id.endsWith("__")) &&
        id.encodeToByteArray().size <= 1500
