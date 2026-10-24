package io.github.basekt.data.mongo

import io.github.basekt.data.AggregateFunction
import io.github.basekt.data.AggregateGroup
import io.github.basekt.data.AggregatePage
import io.github.basekt.data.AggregateQuery
import io.github.basekt.data.AVERAGE_SCALE
import io.github.basekt.data.Direction
import io.github.basekt.schema.Field
import io.github.basekt.schema.FieldType
import io.github.basekt.schema.Resource
import io.github.basekt.schema.TimeBucket
import io.github.basekt.schema.Value
import org.bson.BsonArray
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonNull
import org.bson.BsonNumber
import org.bson.BsonString
import org.bson.BsonValue
import java.time.ZoneOffset

/*
 * An aggregate is one pipeline: $match, a $group whose key holds the group fields (named g0, g1, ...) and whose
 * accumulators are named after the measure they serve, an $addFields that turns them into the measures' values (v0, v1,
 * ...), then the $sort and $limit. MongoDB puts nulls first when ascending, so each sort key gets a 0-or-1 flag first, as
 * a list does.
 */

private fun doc(key: String, value: BsonValue) = BsonDocument(key, value)

private fun arr(vararg values: BsonValue) = BsonArray(values.toList())

private fun path(name: String) = BsonString("\$$name")

private val missing = BsonString("missing")

/** The pipeline that computes [query] over [mapping]'s collection, one more group than the limit, and how to read its groups. */
internal class AggregatePlan(private val mapping: DocumentMapping, private val query: AggregateQuery) {
    private val resource = mapping.resource
    private val groupFields: List<Field?> = query.groupBy.map { if (it == Resource.ID) null else resource.field(it)!! }

    val pipeline: List<BsonDocument>

    init {
        val match = query.filter?.let { mapping.predicate(it) } ?: BsonDocument()

        val key = BsonDocument()
        query.groupBy.forEachIndexed { index, field ->
            val bucket = query.buckets[field]
            key["g$index"] = if (bucket != null) {
                doc(
                    "\$dateTrunc",
                    BsonDocument("date", path(field)).append("unit", BsonString(bucket.unit())).append("timezone", BsonString(if (query.timeZone == ZoneOffset.UTC) "UTC" else query.timeZone.id))
                        .also { if (bucket == TimeBucket.WEEK) it["startOfWeek"] = BsonString("monday") },
                )
            } else {
                // A missing field and a null one are the same group.
                doc("\$ifNull", arr(path(mapping.key(field)), BsonNull.VALUE))
            }
        }

        val group = BsonDocument("_id", if (query.groupBy.isEmpty()) BsonNull.VALUE else key)
        val values = BsonDocument()
        query.measures.forEachIndexed { index, measure ->
            val field = measure.field?.let { mapping.key(it) }
            val set = field?.let { present(it) }
            when (measure.function) {
                AggregateFunction.COUNT -> {
                    group["c$index"] = doc("\$sum", if (set == null) BsonInt32(1) else doc("\$cond", arr(set, BsonInt32(1), BsonInt32(0))))
                    values["v$index"] = path("c$index")
                }
                AggregateFunction.SUM -> {
                    group["s$index"] = doc("\$sum", path(field!!))
                    group["n$index"] = doc("\$sum", doc("\$cond", arr(set!!, BsonInt32(1), BsonInt32(0))))
                    values["v$index"] = doc("\$cond", arr(doc("\$eq", arr(path("n$index"), BsonInt32(0))), BsonNull.VALUE, path("s$index")))
                }
                AggregateFunction.AVG -> {
                    group["s$index"] = doc("\$sum", path(field!!))
                    group["n$index"] = doc("\$sum", doc("\$cond", arr(set!!, BsonInt32(1), BsonInt32(0))))
                    val mean = doc("\$round", arr(doc("\$divide", arr(doc("\$toDecimal", path("s$index")), path("n$index"))), BsonInt32(AVERAGE_SCALE)))
                    values["v$index"] = doc("\$cond", arr(doc("\$eq", arr(path("n$index"), BsonInt32(0))), BsonNull.VALUE, mean))
                }
                AggregateFunction.MIN, AggregateFunction.MAX -> {
                    group["c$index"] = doc(if (measure.function == AggregateFunction.MIN) "\$min" else "\$max", path(field!!))
                    values["v$index"] = path("c$index")
                }
            }
        }

        // Sort by the requested keys, then by every group field ascending, so the order is total.
        val sorts = query.sort.map { sort ->
            val index = query.measures.indexOfFirst { it.name == sort.field }
            (if (index >= 0) "v$index" else "_id.g${query.groupBy.indexOf(sort.field)}") to sort.direction
        }.let { requested -> requested + query.groupBy.indices.map { "_id.g$it" to Direction.ASC }.filter { tie -> requested.none { it.first == tie.first } } }
        val flags = BsonDocument()
        val order = BsonDocument()
        sorts.forEachIndexed { index, (name, direction) ->
            val flag = "__null$index"
            val sign = if (direction == Direction.ASC) 1 else -1
            flags[flag] = doc("\$cond", arr(doc("\$eq", arr(doc("\$ifNull", arr(path(name), BsonNull.VALUE)), BsonNull.VALUE)), BsonInt32(1), BsonInt32(0)))
            order[flag] = BsonInt32(sign)
            order[name] = BsonInt32(sign)
        }

        val project = BsonDocument("_id", BsonInt32(1)).also { p -> values.keys.forEach { p[it] = BsonInt32(1) } }
        pipeline = buildList {
            add(doc("\$match", match))
            add(doc("\$group", group))
            add(doc("\$addFields", values))
            // One group of everything has nothing to sort.
            if (!order.isEmpty()) {
                add(doc("\$addFields", flags))
                add(doc("\$sort", order))
            }
            add(doc("\$limit", BsonInt32(query.limit + 1)))
            add(doc("\$project", project))
        }
    }

    /** Reads the pipeline's [documents] as a page of groups. */
    fun page(documents: List<BsonDocument>): AggregatePage {
        // No group fields means one group, even of no records.
        val rows = if (documents.isEmpty() && query.groupBy.isEmpty()) listOf(BsonDocument()) else documents
        val groups = rows.take(query.limit).map { row ->
            val key = row["_id"] as? BsonDocument ?: BsonDocument()
            val keyRecord = LinkedHashMap<String, Value?>()
            query.groupBy.forEachIndexed { index, field -> keyRecord[field] = decodeKey(field, key["g$index"]) }
            val valueMap = LinkedHashMap<String, Value?>()
            query.measures.forEachIndexed { index, measure -> valueMap[measure.name] = decodeMeasure(measure.function, measure.field, row["v$index"]) }
            AggregateGroup(keyRecord, valueMap)
        }
        return AggregatePage(groups, rows.size > query.limit)
    }

    private fun decodeKey(field: String, bson: BsonValue?): Value? = mapping.decodeValue(typeOf(field), bson)

    private fun decodeMeasure(function: AggregateFunction, field: String?, bson: BsonValue?): Value? = when (function) {
        AggregateFunction.COUNT -> Value.Integer((bson as? BsonNumber)?.longValue() ?: 0)
        AggregateFunction.AVG -> mapping.decodeValue(FieldType.DECIMAL, bson)
        AggregateFunction.SUM, AggregateFunction.MIN, AggregateFunction.MAX -> mapping.decodeValue(typeOf(field!!), bson)
    }

    private fun typeOf(field: String): FieldType = if (field == Resource.ID) FieldType.STRING else resource.field(field)!!.type
}

private fun TimeBucket.unit(): String = when (this) {
    TimeBucket.DAY -> "day"
    TimeBucket.WEEK -> "week"
    TimeBucket.MONTH -> "month"
    TimeBucket.YEAR -> "year"
}

/** A match expression for "the field holds a value": neither null nor missing. */
private fun present(field: String): BsonValue =
    doc("\$not", arr(doc("\$in", arr(doc("\$type", path(field)), arr(BsonString("null"), missing)))))
