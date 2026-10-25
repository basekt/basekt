package io.github.basekt.data.exposed

import io.github.basekt.data.Comparison
import io.github.basekt.data.CursorKey
import io.github.basekt.data.Direction
import io.github.basekt.data.Filter
import io.github.basekt.data.InvalidQueryException
import io.github.basekt.data.Sort
import io.github.basekt.schema.IdSpec
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
        if (!table.stored(filter.field).isText) {
            throw InvalidQueryException("'${filter.field}' is kept in ${table.stored(filter.field).sql.phrase} column, so it can't be searched.")
        }
        @Suppress("UNCHECKED_CAST")
        val column = table.column(filter.field) as Column<String?>
        column.isNotNull() and (column.lowerCase() like LikePattern("%${escapeLike(filter.text.lowercase())}%", '\\'))
    }
    is Filter.IsNull -> isNull(table, filter.field, filter.isNull)
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
        val value = after.values[index]
        val beyond: Op<Boolean>? = when {
            key.direction == Direction.ASC && value == null -> null
            key.direction == Direction.ASC -> isNull(table, key.field, true) or compare(table, key.field, Comparison.GT, value!!)
            value == null -> isNull(table, key.field, false)
            else -> compare(table, key.field, Comparison.LT, value)
        }
        if (beyond != null) alternatives += (tied + beyond).allOf()
        tied += if (value == null) isNull(table, key.field, true) else compare(table, key.field, Comparison.EQ, value)
    }
    alternatives += (tied + compare(table, Resource.ID, Comparison.GT, Value.Text(after.id))).allOf()
    return alternatives.anyOf()
}

/** Returns the order of [sort] on [table], ending with the id. */
internal fun ordering(table: ResourceTable, sort: List<Sort>): Array<Pair<Expression<*>, SortOrder>> =
    (sort.flatMap { key -> columnsOf(table, key.field).map { it to key.direction.order() } } + table.keyColumns.map { it to SortOrder.ASC })
        .toTypedArray()

/** The columns [field] sorts by: its own, or the key's for the id. */
private fun columnsOf(table: ResourceTable, field: String): List<Column<Any?>> =
    if (field == Resource.ID) table.keyColumns else listOf(table.column(field))

/** Whether [field] is null, or with [isNull] `false`, set. An id is always set. */
private fun isNull(table: ResourceTable, field: String, isNull: Boolean): Op<Boolean> = when {
    field == Resource.ID && table.composite -> if (isNull) Op.FALSE else Op.TRUE
    isNull -> table.column(field).isNull()
    else -> table.column(field).isNotNull()
}

private fun Direction.order(): SortOrder = when (this) {
    Direction.ASC -> SortOrder.ASC_NULLS_LAST
    Direction.DESC -> SortOrder.DESC_NULLS_FIRST
}

private fun compare(table: ResourceTable, field: String, op: Comparison, value: Value): Op<Boolean> {
    if (field == Resource.ID && table.composite) return compareKey(table, op, (value as Value.Text).value)
    val column = table.column(field)
    val parameter = parameter(column, value, table.stored(field)) ?: return unmatched(field, op, value)
    return column.isNotNull() and comparison(column, op, parameter)
}

private fun comparison(column: Expression<*>, op: Comparison, parameter: Expression<*>): Op<Boolean> = when (op) {
    Comparison.EQ -> EqOp(column, parameter)
    Comparison.NE -> NeqOp(column, parameter)
    Comparison.GT -> GreaterOp(column, parameter)
    Comparison.GTE -> GreaterEqOp(column, parameter)
    Comparison.LT -> LessOp(column, parameter)
    Comparison.LTE -> LessEqOp(column, parameter)
}

/**
 * Compares the id of a table whose key is several columns with [id], column by column: rows sort by the first key
 * column, then the next.
 */
private fun compareKey(table: ResourceTable, op: Comparison, id: String): Op<Boolean> {
    val parts = IdSpec.partsOf(id)
    val values = if (parts.size == table.keyColumns.size) {
        table.keyColumns.indices.map { i -> parameter(table.keyColumns[i], Value.Text(parts[i]), table.keyStored[i]) }
    } else {
        null
    }
    if (values == null || values.any { it == null }) return unmatched(Resource.ID, op, Value.Text(id))
    val columns = table.keyColumns
    fun equalUpTo(count: Int) = (0 until count).map { comparison(columns[it], Comparison.EQ, values[it]!!) }.allOf()
    val strict = if (op == Comparison.GT || op == Comparison.GTE) Comparison.GT else Comparison.LT
    val beyond = columns.indices.map { i -> equalUpTo(i) and comparison(columns[i], strict, values[i]!!) }.anyOf()
    return when (op) {
        Comparison.EQ -> equalUpTo(columns.size)
        Comparison.NE -> not(equalUpTo(columns.size))
        Comparison.GT, Comparison.LT -> beyond
        Comparison.GTE, Comparison.LTE -> beyond or equalUpTo(columns.size)
    }
}

/**
 * The comparison with a value the column can't hold, such as text that isn't a number in a number column: it equals no
 * row and differs from every row, and it can't be ordered.
 */
private fun unmatched(field: String, op: Comparison, value: Value): Op<Boolean> = when (op) {
    Comparison.EQ -> Op.FALSE
    Comparison.NE -> Op.TRUE
    else -> throw InvalidQueryException("'$field' can't be ordered against '$value', which its column can't hold.")
}

/**
 * Binds [value] for a comparison with [column], or returns `null` when the column can't hold it. Integers bind as
 * BIGINT, so any long compares with an int column.
 */
@Suppress("UNCHECKED_CAST")
private fun parameter(column: Column<Any?>, value: Value, stored: Stored): Expression<*>? = when (value) {
    is Value.Integer -> QueryParameter(value.value, LongColumnType())
    else -> stored.toColumn(value)?.let { QueryParameter(it, column.columnType as IColumnType<Any>) }
}

private fun List<Op<Boolean>>.allOf(): Op<Boolean> = reduceOrNull { a, b -> a and b } ?: Op.TRUE

private fun List<Op<Boolean>>.anyOf(): Op<Boolean> = reduceOrNull { a, b -> a or b } ?: Op.FALSE

private fun escapeLike(text: String): String =
    text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
