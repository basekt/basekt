package io.github.basekt.data.mongo

import io.github.basekt.schema.Field
import io.github.basekt.schema.FieldType
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
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

    /** Encodes [value] for [field]. */
    fun encode(field: String, value: Value): BsonValue = when (value) {
        is Value.Text -> if (field in objectIdFields && ObjectId.isValid(value.value)) BsonObjectId(ObjectId(value.value)) else BsonString(value.value)
        // An int field is an int32, and a comparison with it by a long still compares by value.
        is Value.Integer -> if (typeOf(field) == FieldType.INT && value.value in Int.MIN_VALUE..Int.MAX_VALUE) BsonInt32(value.value.toInt()) else BsonInt64(value.value)
        is Value.Decimal -> BsonDecimal128(Decimal128(BigDecimal(value.literal)))
        is Value.Bool -> BsonBoolean(value.value)
        is Value.Timestamp -> BsonDateTime(value.value.toEpochMilliseconds())
        is Value.Items -> throw IllegalArgumentException("A list can't be stored in '${resource.name}.$field': only an action's input may hold one.")
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

    private fun decode(field: Field, bson: BsonValue?): Value? = decodeValue(field.type, bson)

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
        }
    }

    private fun text(bson: BsonValue): String = when (bson) {
        is BsonString -> bson.value
        is BsonObjectId -> bson.value.toHexString()
        else -> bson.toString()
    }
}
