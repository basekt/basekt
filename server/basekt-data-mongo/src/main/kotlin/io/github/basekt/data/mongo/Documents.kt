package io.github.basekt.data.mongo

import io.github.basekt.schema.Field
import io.github.basekt.schema.FieldType
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
import org.bson.BsonArray
import org.bson.BsonBoolean
import org.bson.BsonDateTime
import org.bson.BsonDecimal128
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonInt64
import org.bson.BsonNull
import org.bson.BsonNumber
import org.bson.BsonObjectId
import org.bson.BsonString
import org.bson.BsonValue
import org.bson.types.Decimal128
import org.bson.types.ObjectId
import java.math.BigDecimal
import kotlin.time.Instant

/** Document key of the record's id. */
internal const val ID_KEY = "_id"

/** How one resource's records become documents and back. */
internal class DocumentMapping(val resource: Resource, mapping: MongoMapping) {
    val collection: String = mapping.collectionOf(resource.name)
    private val objectIdFields: Set<String> = mapping.objectIds[resource.name].orEmpty()

    /** The document key of [field], which may be [Resource.ID]. */
    fun key(field: String): String = if (field == Resource.ID) ID_KEY else field

    /** The type of [field], counting the id as text. */
    private fun typeOf(field: String): FieldType = if (field == Resource.ID) FieldType.STRING else resource.field(field)!!.type

    /**
     * Encodes [value] for [field]. A list is an array of its items, and an object a sub-document whose id, if it has
     * one, is its `_id`. An item of a list named in [MongoMapping.objectIds] is stored as an ObjectId, and so is an
     * object's id when `field.id` is named.
     */
    fun encode(field: String, value: Value): BsonValue = encodeAs(typeOf(field), field in objectIdFields, value, field)

    private fun encodeAs(type: FieldType, objectId: Boolean, value: Value, field: String): BsonValue = when (value) {
        is Value.Text -> if (objectId && ObjectId.isValid(value.value)) BsonObjectId(ObjectId(value.value)) else BsonString(value.value)
        // An int field is an int32, and a comparison with it by a long still compares by value.
        is Value.Integer -> if (type == FieldType.INT && value.value in Int.MIN_VALUE..Int.MAX_VALUE) BsonInt32(value.value.toInt()) else BsonInt64(value.value)
        is Value.Decimal -> BsonDecimal128(Decimal128(BigDecimal(value.literal)))
        is Value.Bool -> BsonBoolean(value.value)
        is Value.Timestamp -> BsonDateTime(value.value.toEpochMilliseconds())
        is Value.Items -> BsonArray(value.values.map { encodeAs(type, objectId, it, field) })
        is Value.Object -> {
            val list = resource.field(field)
            val document = BsonDocument()
            value.values[Resource.ID]?.let { document[ID_KEY] = encodeAs(FieldType.STRING, "$field.${Resource.ID}" in objectIdFields, it, field) }
            for (sub in list?.fields.orEmpty()) {
                if (sub.name in value.values) document[sub.name] = value.values[sub.name]?.let { encodeAs(sub.type, false, it, field) } ?: BsonNull.VALUE
            }
            document
        }
    }

    /** Encodes an optional value: `null` is stored as a BSON null. */
    fun encodeOrNull(field: String, value: Value?): BsonValue = value?.let { encode(field, it) } ?: BsonNull.VALUE

    /** The document of [record], with every field and `_id`. */
    fun toDocument(id: String, record: Record): BsonDocument {
        val document = BsonDocument()
        document[ID_KEY] = encode(Resource.ID, Value.Text(id))
        for (field in resource.fields) document[field.name] = encodeOrNull(field.name, record[field.name])
        return document
    }

    /** The record in [document]: every field of the resource, `null` for what the document doesn't hold. */
    fun toRecord(document: BsonDocument): Record {
        val record = LinkedHashMap<String, Value?>(resource.fields.size + 1)
        record[Resource.ID] = Value.Text(text(document[ID_KEY]!!))
        for (field in resource.fields) record[field.name] = decode(field, document[field.name])
        return record
    }

    private fun decode(field: Field, bson: BsonValue?): Value? {
        if (bson == null || bson is BsonNull) return null
        if (field.list) {
            val array = bson as? BsonArray ?: return null
            val item = field.copy(list = false)
            return Value.Items(array.mapNotNull { decode(item, it) })
        }
        if (field.type == FieldType.OBJECT) {
            val document = bson as? BsonDocument ?: return null
            val values = linkedMapOf<String, Value?>()
            if (field.itemIds) document[ID_KEY]?.takeUnless { it is BsonNull }?.let { values[Resource.ID] = Value.Text(text(it)) }
            for (sub in field.fields.orEmpty()) if (document.containsKey(sub.name)) values[sub.name] = decode(sub, document[sub.name])
            return Value.Object(values)
        }
        return decodeValue(field.type, bson)
    }

    /** Reads [bson] as a value of [type]: what a field of that type holds. A number may be any numeric type. */
    fun decodeValue(type: FieldType, bson: BsonValue?): Value? {
        if (bson == null || bson is BsonNull) return null
        return when (type) {
            FieldType.STRING, FieldType.ENUM, FieldType.REFERENCE, FieldType.FILE -> Value.Text(text(bson))
            FieldType.INT, FieldType.LONG -> (bson as? BsonNumber)?.let { Value.Integer(it.longValue()) }
            FieldType.DECIMAL -> when (bson) {
                is BsonDecimal128 -> Value.Decimal.of(bson.value.bigDecimalValue().stripTrailingZeros().toPlainString())
                is BsonNumber -> Value.Decimal.of(BigDecimal.valueOf(bson.doubleValue()).stripTrailingZeros().toPlainString())
                else -> null
            }
            FieldType.BOOL -> (bson as? BsonBoolean)?.let { Value.Bool(it.value) }
            FieldType.INSTANT -> (bson as? BsonDateTime)?.let { Value.Timestamp(Instant.fromEpochMilliseconds(it.value)) }
            FieldType.OBJECT -> null
        }
    }

    private fun text(bson: BsonValue): String = when (bson) {
        is BsonString -> bson.value
        is BsonObjectId -> bson.value.toHexString()
        else -> bson.toString()
    }
}
