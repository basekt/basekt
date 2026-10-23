package io.github.basekt.data.exposed

import io.github.basekt.data.AggregateFunction
import io.github.basekt.data.AggregateGroup
import io.github.basekt.data.AggregatePage
import io.github.basekt.data.AggregateQuery
import io.github.basekt.data.Direction
import io.github.basekt.data.Measure
import io.github.basekt.data.averageOf
import io.github.basekt.data.fieldType
import io.github.basekt.schema.FieldType
import io.github.basekt.schema.Value
import org.jetbrains.exposed.v1.core.Count
import org.jetbrains.exposed.v1.core.DecimalColumnType
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.Max
import org.jetbrains.exposed.v1.core.Min
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Sum
import org.jetbrains.exposed.v1.jdbc.select
import java.math.BigDecimal

/** A sum wide enough for any int, long or decimal column, read back as a [BigDecimal]. */
private val WideSum = DecimalColumnType(65, 30)

/**
 * Runs [query] on [table] as one `SELECT … GROUP BY`. An average is selected as its sum and count and divided here, so
 * every database gives the same exact value. Sorting by an average happens here too, after fetching every group.
 */
@Suppress("UNCHECKED_CAST")
internal fun aggregate(table: ResourceTable, query: AggregateQuery): AggregatePage {
    val resource = table.resource
    val groupColumns = query.groupBy.map { table.column(it) }
    val parts = query.measures.associateWith { measure ->
        val column = measure.field?.let { table.column(it) }
        when (measure.function) {
            AggregateFunction.COUNT -> listOf(Count(column ?: table.id))
            AggregateFunction.SUM -> listOf(Sum(column!! as Expression<BigDecimal?>, WideSum))
            AggregateFunction.AVG -> listOf(Sum(column!! as Expression<BigDecimal?>, WideSum), Count(column))
            AggregateFunction.MIN -> listOf(Min(column!! as Expression<Comparable<Any>?>, column.columnType as IColumnType<Comparable<Any>>))
            AggregateFunction.MAX -> listOf(Max(column!! as Expression<Comparable<Any>?>, column.columnType as IColumnType<Comparable<Any>>))
        }
    }
    val sortsByAverage = query.sort.any { key -> query.measures.any { it.name == key.field && it.function == AggregateFunction.AVG } }
    val ordering = buildList<Pair<Expression<*>, SortOrder>> {
        if (!sortsByAverage) {
            for (key in query.sort) {
                val expression = query.measures.firstOrNull { it.name == key.field }?.let { parts.getValue(it).first() }
                    ?: table.column(key.field)
                add(expression to key.direction.order())
            }
        }
        for (column in groupColumns) add(column to SortOrder.ASC_NULLS_LAST)
    }
    val select = table.select(groupColumns + parts.values.flatten())
        .apply { query.filter?.let { where(predicate(table, it)) } }
        .apply { if (groupColumns.isNotEmpty()) groupBy(*groupColumns.toTypedArray()) }
        .orderBy(*ordering.toTypedArray())
        .apply { if (!sortsByAverage) limit(query.limit + 1) }
    var groups = select.map { row ->
        AggregateGroup(
            key = query.groupBy.zip(groupColumns).associate { (field, column) ->
                field to row[column]?.let { fromColumn(resource.fieldType(field), it) }
            },
            values = query.measures.associate { measure -> measure.name to value(row, measure, parts.getValue(measure), resource.fieldType(measure.field)) },
        )
    }
    if (sortsByAverage) groups = groups.sortedWith(averageOrder(query))
    return AggregatePage(groups.take(query.limit), groups.size > query.limit)
}

private fun io.github.basekt.schema.Resource.fieldType(field: String?): FieldType? = field?.let { fieldType(it) }

@Suppress("UNCHECKED_CAST")
private fun value(row: ResultRow, measure: Measure, parts: List<Expression<*>>, type: FieldType?): Value? {
    fun count(expression: Expression<*>) = (row[expression as Expression<Long>]).toLong()
    fun sum(expression: Expression<*>): Value? = row[expression as Expression<BigDecimal?>]?.let { total ->
        if (type == FieldType.DECIMAL) Value.Decimal.of(total.stripTrailingZeros().toPlainString()) else Value.Integer(total.longValueExact())
    }
    return when (measure.function) {
        AggregateFunction.COUNT -> Value.Integer(count(parts.single()))
        AggregateFunction.SUM -> sum(parts.single())
        AggregateFunction.AVG -> averageOf(sum(parts[0]), count(parts[1]))
        AggregateFunction.MIN, AggregateFunction.MAX -> row[parts.single() as Expression<Any?>]?.let { fromColumn(type!!, it) }
    }
}

/** The order of groups when a sort key is an average, which only this side computes exactly. */
private fun averageOrder(query: AggregateQuery): Comparator<AggregateGroup> = Comparator { a, b ->
    fun valueOf(group: AggregateGroup, name: String) = if (name in query.groupBy) group.key[name] else group.values[name]
    for (key in query.sort) {
        val ascending = compareNullsLast(valueOf(a, key.field), valueOf(b, key.field))
        val result = if (key.direction == Direction.ASC) ascending else -ascending
        if (result != 0) return@Comparator result
    }
    for (field in query.groupBy) {
        val result = compareNullsLast(a.key[field], b.key[field])
        if (result != 0) return@Comparator result
    }
    0
}

private fun compareNullsLast(a: Value?, b: Value?): Int = when {
    a == null && b == null -> 0
    a == null -> 1
    b == null -> -1
    else -> a.compareTo(b)
}

private fun Direction.order(): SortOrder = when (this) {
    Direction.ASC -> SortOrder.ASC_NULLS_LAST
    Direction.DESC -> SortOrder.DESC_NULLS_FIRST
}
