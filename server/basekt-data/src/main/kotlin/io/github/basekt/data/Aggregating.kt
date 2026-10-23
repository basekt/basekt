package io.github.basekt.data

import io.github.basekt.schema.FieldType
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * An optional [DataSource] capability: grouped totals, such as stock per item and site or sales per day, computed by
 * the source instead of by loading every record. Callers check for it with `source as? Aggregating`.
 *
 * Semantics every adapter follows:
 * - [AggregateFunction.COUNT] without a field counts records; with one, the records whose field isn't null.
 * - [AggregateFunction.SUM] of int and long fields is an integer, of decimal fields a decimal; `null` when the
 *   group has no values.
 * - [AggregateFunction.AVG] of a number field is the exact decimal [averageOf] gives.
 * - [AggregateFunction.MIN] and [AggregateFunction.MAX] work on numbers, text and instants, and keep the field's type.
 * - Records whose group field is null form a group of their own, which sorts after the others when ascending.
 * - Groups come in [AggregateQuery.sort] order, then by every group field ascending, so the order is total.
 */
public interface Aggregating {
    /**
     * Groups the records of [resource] that match [query]'s filter by its group fields, and computes its measures for
     * each group. Throws [InvalidQueryException] if the query doesn't fit the resource; see [checkAggregate].
     */
    public suspend fun aggregate(resource: Resource, query: AggregateQuery): AggregatePage
}

/** What [Aggregating.aggregate] computes. */
public data class AggregateQuery(
    /** The fields whose values make a group, most significant first. None means one group of every record. */
    val groupBy: List<String> = emptyList(),
    /** What to compute for each group. */
    val measures: List<Measure>,
    /** Which records count, or `null` for all of them. */
    val filter: Filter? = null,
    /** The order of the groups, by group fields or measure names. Group fields ascending follow. */
    val sort: List<Sort> = emptyList(),
    /** The most groups to return, at least 1. */
    val limit: Int = 1000,
) {
    init {
        require(measures.isNotEmpty()) { "An aggregate needs at least one measure." }
        require(limit >= 1) { "An aggregate's limit must be at least 1, not $limit." }
    }
}

/** A value computed for each group: [function] of [field], called [name] in the result. */
public data class Measure(
    /** What to compute. */
    val function: AggregateFunction,
    /** The field to compute it over, or `null` for [AggregateFunction.COUNT] of records. */
    val field: String? = null,
    /** The name of the value in each group, `count`, `count_<field>`, `sum_<field>` and so on by default. */
    val name: String = function.name.lowercase() + (field?.let { "_$it" } ?: ""),
)

/** A function a [Measure] computes. */
public enum class AggregateFunction {
    /** How many records, or how many with the field set. */
    COUNT,

    /** The total of a number field. */
    SUM,

    /** The smallest value. */
    MIN,

    /** The largest value. */
    MAX,

    /** The mean of a number field. */
    AVG,
}

/** One group: its [key], the group fields' values, and the measures' [values]. */
public data class AggregateGroup(
    /** The value of each group field. */
    val key: Record,
    /** The value of each measure, by name. */
    val values: Map<String, Value?>,
)

/** The groups an aggregate returns, and whether the limit cut any off. */
public data class AggregatePage(
    /** The groups, in order. */
    val groups: List<AggregateGroup>,
    /** Whether there are more groups than [groups] holds. */
    val hasMore: Boolean,
)

/**
 * Checks that [query] fits this resource: its fields exist, each function fits its field's type, measure names are
 * unique and don't clash with group fields, and sort keys name a group field or a measure. Throws
 * [InvalidQueryException] otherwise. Adapters call it first.
 */
public fun Resource.checkAggregate(query: AggregateQuery) {
    checkQuery(Query(query.filter))
    for (field in query.groupBy) fieldType(field)
    if (query.groupBy.size != query.groupBy.toSet().size) throw InvalidQueryException("A field can't be grouped by twice.")
    for (measure in query.measures) {
        val field = measure.field
        if (field == null) {
            if (measure.function != AggregateFunction.COUNT) throw InvalidQueryException("${measure.function.name.lowercase()} needs a field.")
            continue
        }
        val type = fieldType(field)
        val fits = when (measure.function) {
            AggregateFunction.COUNT -> true
            AggregateFunction.SUM, AggregateFunction.AVG -> type.isNumber()
            AggregateFunction.MIN, AggregateFunction.MAX -> type.isNumber() || type == FieldType.INSTANT ||
                type == FieldType.STRING || type == FieldType.ENUM || type == FieldType.REFERENCE
        }
        if (!fits) throw InvalidQueryException("${measure.function.name.lowercase()} can't be computed over the ${type.name.lowercase()} field '$field'.")
    }
    val names = query.measures.map { it.name }
    if (names.size != names.toSet().size) throw InvalidQueryException("Measure names must be unique: ${names.joinToString()}.")
    names.firstOrNull { it in query.groupBy }?.let { throw InvalidQueryException("'$it' is both a group field and a measure.") }
    for (sort in query.sort) {
        if (sort.field !in query.groupBy && sort.field !in names) {
            throw InvalidQueryException("'${sort.field}' is neither a group field nor a measure, so groups can't be sorted by it.")
        }
    }
}

/** The mean of values that add up to [sum] over [count] of them: exact to 12 decimal places, half-even. */
public fun averageOf(sum: Value?, count: Long): Value? {
    if (sum == null || count == 0L) return null
    val total = when (sum) {
        is Value.Integer -> BigDecimal.valueOf(sum.value)
        is Value.Decimal -> BigDecimal(sum.literal)
        else -> throw InvalidQueryException("'$sum' can't be averaged.")
    }
    val mean = total.divide(BigDecimal.valueOf(count), AVERAGE_SCALE, RoundingMode.HALF_EVEN).stripTrailingZeros()
    return Value.Decimal.of(mean.toPlainString())
}

/** The decimal places of [averageOf]. */
public const val AVERAGE_SCALE: Int = 12

private fun FieldType.isNumber(): Boolean = this == FieldType.INT || this == FieldType.LONG || this == FieldType.DECIMAL
