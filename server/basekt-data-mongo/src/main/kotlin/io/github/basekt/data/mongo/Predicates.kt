package io.github.basekt.data.mongo

import io.github.basekt.data.Comparison
import io.github.basekt.data.CursorKey
import io.github.basekt.data.Direction
import io.github.basekt.data.Filter
import io.github.basekt.data.Sort
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
import org.bson.BsonArray
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonNull
import org.bson.BsonString
import org.bson.BsonValue

/*
 * Filters become match documents that never match a null: every comparison skips null and missing fields, so `NOT` of a
 * comparison matches them, as Query's semantics require. MongoDB's own `$ne` and `$nin` match null and missing fields,
 * which is why NE is spelled out, and `Not` is `$nor`, the complement of its operand.
 */

private fun doc(key: String, value: BsonValue) = BsonDocument(key, value)

private fun allOf(documents: List<BsonDocument>): BsonDocument = when (documents.size) {
    0 -> BsonDocument()
    1 -> documents.single()
    else -> doc("\$and", BsonArray(documents))
}

private fun anyOf(documents: List<BsonDocument>): BsonDocument = when (documents.size) {
    // `$nor` of a document that matches everything matches nothing.
    0 -> doc("\$nor", BsonArray(listOf(BsonDocument())))
    1 -> documents.single()
    else -> doc("\$or", BsonArray(documents))
}

internal fun DocumentMapping.isNull(field: String): BsonDocument = doc(key(field), BsonNull.VALUE)

internal fun DocumentMapping.isNotNull(field: String): BsonDocument = doc(key(field), doc("\$ne", BsonNull.VALUE))

internal fun DocumentMapping.compare(field: String, op: Comparison, value: Value): BsonDocument {
    val bson = encode(field, value)
    val k = key(field)
    return when (op) {
        Comparison.EQ -> doc(k, bson)
        Comparison.NE -> allOf(listOf(doc(k, doc("\$ne", bson)), isNotNull(field)))
        Comparison.GT -> doc(k, doc("\$gt", bson))
        Comparison.GTE -> doc(k, doc("\$gte", bson))
        Comparison.LT -> doc(k, doc("\$lt", bson))
        Comparison.LTE -> doc(k, doc("\$lte", bson))
    }
}

/** The match document for [filter]. */
internal fun DocumentMapping.predicate(filter: Filter): BsonDocument = when (filter) {
    is Filter.Compare -> compare(filter.field, filter.op, filter.value)
    is Filter.In -> doc(key(filter.field), doc("\$in", BsonArray(filter.values.map { encode(filter.field, it) })))
    is Filter.Contains -> doc(key(filter.field), BsonDocument("\$regex", BsonString(escapeRegex(filter.text))).append("\$options", BsonString("i")))
    is Filter.IsNull -> if (filter.isNull) isNull(filter.field) else isNotNull(filter.field)
    is Filter.And -> allOf(filter.filters.map { predicate(it) })
    is Filter.Or -> anyOf(filter.filters.map { predicate(it) })
    is Filter.Not -> doc("\$nor", BsonArray(listOf(predicate(filter.filter))))
}

/**
 * The match document for the records strictly after [after] in [sort] order, then id order: nulls after values when
 * ascending, and before them when descending.
 */
internal fun DocumentMapping.keysetAfter(sort: List<Sort>, after: CursorKey): BsonDocument {
    val alternatives = mutableListOf<BsonDocument>()
    val tied = mutableListOf<BsonDocument>()
    for ((index, key) in sort.withIndex()) {
        val value = after.values[index]
        val beyond: BsonDocument? = when {
            key.direction == Direction.ASC && value == null -> null
            key.direction == Direction.ASC -> anyOf(listOf(isNull(key.field), compare(key.field, Comparison.GT, value!!)))
            value == null -> isNotNull(key.field)
            else -> compare(key.field, Comparison.LT, value)
        }
        if (beyond != null) alternatives += allOf(tied + beyond)
        tied += if (value == null) isNull(key.field) else compare(key.field, Comparison.EQ, value)
    }
    alternatives += allOf(tied + compare(Resource.ID, Comparison.GT, Value.Text(after.id)))
    return anyOf(alternatives)
}

/**
 * The `$sort` stage for [sort], ending with the id: nulls last when ascending and first when descending, which
 * MongoDB doesn't do by itself (it puts nulls first when ascending). [nullFlags] are the `$addFields` that make that
 * possible, one `0 or 1` per key.
 */
internal fun DocumentMapping.ordering(sort: List<Sort>): Pair<BsonDocument, BsonDocument> {
    val flags = BsonDocument()
    val order = BsonDocument()
    for ((index, sortKey) in sort.withIndex()) {
        val flag = "__null$index"
        val direction = if (sortKey.direction == Direction.ASC) 1 else -1
        // Null and missing both count as null.
        flags[flag] = doc("\$cond", BsonArray(listOf(doc("\$eq", BsonArray(listOf(doc("\$ifNull", BsonArray(listOf(BsonString("\$${key(sortKey.field)}"), BsonNull.VALUE))), BsonNull.VALUE))), BsonInt32(1), BsonInt32(0))))
        order[flag] = BsonInt32(direction)
        order[key(sortKey.field)] = BsonInt32(direction)
    }
    order[ID_KEY] = BsonInt32(1)
    return flags to order
}

/** Escapes the characters a regular expression gives a meaning to. */
internal fun escapeRegex(text: String): String = buildString {
    for (c in text) {
        if (c in "\\^$.|?*+()[]{}") append('\\')
        append(c)
    }
}
