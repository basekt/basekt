package io.github.basekt.data.exposed

import io.github.basekt.data.Comparison
import io.github.basekt.data.CursorKey
import io.github.basekt.data.Direction
import io.github.basekt.data.Filter
import io.github.basekt.data.Sort
import io.github.basekt.data.fieldType
import io.github.basekt.schema.FieldType
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.EqOp
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.GreaterEqOp
import org.jetbrains.exposed.v1.core.GreaterOp
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.LessEqOp
import org.jetbrains.exposed.v1.core.LessOp
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.NeqOp
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.core.or

/*
 * Filters become predicates that are never SQL NULL: every comparison is guarded by `IS NOT NULL`, so it is false
 * on a null field instead of unknown. That keeps `NOT` matching null comparisons, as Query's semantics require.
 */

/** Returns the predicate for [filter] on [table]. */
internal fun predicate(table: ResourceTable, filter: Filter): Op<Boolean> = when (filter) {
    is Filter.Compare -> compare(table, filter.field, filter.op, filter.value)
    is Filter.In -> filter.values.map { compare(table, filter.field, Comparison.EQ, it) }.anyOf()
    is Filter.Contains -> {
        @Suppress("UNCHECKED_CAST")
        val column = table.column(filter.field) as Column<String?>
        column.isNotNull() and (column.lowerCase() like LikePattern("%${escapeLike(filter.text.lowercase())}%", '\\'))
    }
    is Filter.IsNull -> table.column(filter.field).let { if (filter.isNull) it.isNull() else it.isNotNull() }
    is Filter.And -> filter.filters.map { predicate(table, it) }.allOf()
    is Filter.Or -> filter.filters.map { predicate(table, it) }.anyOf()
    is Filter.Not -> not(predicate(table, filter.filter))
}

/**
 * Returns the predicate for the records strictly after [after] in [sort] order, then id order: nulls after values
 * when ascending, and before them when descending.
 */
internal fun keysetAfter(table: ResourceTable, sort: List<Sort>, after: CursorKey): Op<Boolean> {
    val alternatives = mutableListOf<Op<Boolean>>()
    val tied = mutableListOf<Op<Boolean>>()
    for ((index, key) in sort.withIndex()) {
        val column = table.column(key.field)
        val value = after.values[index]
        val beyond: Op<Boolean>? = when {
            key.direction == Direction.ASC && value == null -> null
            key.direction == Direction.ASC -> column.isNull() or compare(table, key.field, Comparison.GT, value!!)
            value == null -> column.isNotNull()
            else -> compare(table, key.field, Comparison.LT, value)
        }
        if (beyond != null) alternatives += (tied + beyond).allOf()
        tied += if (value == null) column.isNull() else compare(table, key.field, Comparison.EQ, value)
    }
    alternatives += (tied + compare(table, Resource.ID, Comparison.GT, Value.Text(after.id))).allOf()
    return alternatives.anyOf()
}

/** Returns the order of [sort] on [table], ending with the id. */
internal fun ordering(table: ResourceTable, sort: List<Sort>): Array<Pair<Expression<*>, SortOrder>> =
    (sort.map { table.column(it.field) to it.direction.order() } + (table.id to SortOrder.ASC)).toTypedArray()

private fun Direction.order(): SortOrder = when (this) {
    Direction.ASC -> SortOrder.ASC_NULLS_LAST
    Direction.DESC -> SortOrder.DESC_NULLS_FIRST
}

private fun compare(table: ResourceTable, field: String, op: Comparison, value: Value): Op<Boolean> {
    val column = table.column(field)
    val parameter = parameter(column, value, table.resource.fieldType(field))
    val comparison = when (op) {
        Comparison.EQ -> EqOp(column, parameter)
        Comparison.NE -> NeqOp(column, parameter)
        Comparison.GT -> GreaterOp(column, parameter)
        Comparison.GTE -> GreaterEqOp(column, parameter)
        Comparison.LT -> LessOp(column, parameter)
        Comparison.LTE -> LessEqOp(column, parameter)
    }
    return column.isNotNull() and comparison
}

/** Binds [value] for a comparison with [column]. Integers bind as BIGINT, so any long compares with an int column. */
@Suppress("UNCHECKED_CAST")
private fun parameter(column: Column<Any?>, value: Value, type: FieldType): Expression<*> = when (value) {
    is Value.Integer -> QueryParameter(value.value, LongColumnType())
    else -> QueryParameter(value.toColumn(type), column.columnType as IColumnType<Any>)
}

private fun List<Op<Boolean>>.allOf(): Op<Boolean> = reduceOrNull { a, b -> a and b } ?: Op.TRUE

private fun List<Op<Boolean>>.anyOf(): Op<Boolean> = reduceOrNull { a, b -> a or b } ?: Op.FALSE

private fun escapeLike(text: String): String =
    text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
