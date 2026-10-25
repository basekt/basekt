package io.github.basekt.data.firestore

import com.google.cloud.firestore.FieldPath
import io.github.basekt.data.Comparison
import io.github.basekt.data.CursorKey
import io.github.basekt.data.Direction
import io.github.basekt.data.Filter
import io.github.basekt.data.Sort
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
import com.google.cloud.firestore.Filter as Condition
import com.google.cloud.firestore.Query as FirestoreQuery

/** Firestore's limit on the values of an `in` and the disjunctions of a query. */
private const val MAX_DISJUNCTIONS = 30

/**
 * How one query runs: [conditions] for Firestore to filter by, a [residual] to check in memory, and either Firestore's
 * [order] or, when it is `null`, a sort in memory. [reasons] say why any part runs in memory.
 */
internal class QueryPlan(
    val conditions: List<Condition>,
    val residual: Filter?,
    /** Firestore's order, ending with the id, or `null` when the records are sorted in memory. */
    val order: List<Sort>?,
    /** True when the filter can match nothing, so nothing needs to be read. */
    val nothing: Boolean,
    val reasons: List<String>,
) {
    /** Whether part of the work is done in memory, over documents Firestore returns. */
    val inMemory: Boolean get() = residual != null || order == null

    /** The Firestore query over [mapping]'s collection: the conditions and, for a list in Firestore's order, its order. */
    fun query(mapping: DocumentMapping): FirestoreQuery {
        var query: FirestoreQuery = mapping.collection
        when (conditions.size) {
            0 -> {}
            1 -> query = query.where(conditions.single())
            else -> query = query.where(Condition.and(*conditions.toTypedArray()))
        }
        for (key in order.orEmpty()) query = query.orderBy(path(key.field), if (key.direction == Direction.ASC) FirestoreQuery.Direction.ASCENDING else FirestoreQuery.Direction.DESCENDING)
        return query
    }

    /** The values of [after] for Firestore's [order], to start the query after it. */
    fun cursorValues(mapping: DocumentMapping, sort: List<Sort>, after: CursorKey): Array<Any?> = order!!.map { key ->
        if (key.field == Resource.ID) mapping.collection.document(after.id)
        else mapping.encodeOrNull(key.field, after.values[sort.indexOfFirst { it.field == key.field }])
    }.toTypedArray()
}

/** Splits queries on one resource into what Firestore answers and what is checked in memory. */
internal class Planner(private val mapping: DocumentMapping) {
    /**
     * Plans a list. Firestore orders the records when every sort key is a field it orders as BaseKt does and that every
     * matching record has, since Firestore leaves out of a sorted query the documents that lack the field, and puts
     * nulls first. A field is known to be set when the filter compares it or requires it to be set, or when the schema
     * makes it required. Otherwise the matching records are sorted in memory.
     */
    fun forList(filter: Filter?, sort: List<Sort>): QueryPlan {
        val keys = sort.indexOfFirst { it.field == Resource.ID }.let { if (it < 0) sort else sort.take(it + 1) }
        val fields = keys.map { it.field }.filter { it != Resource.ID }
        val reasons = mutableListOf<String>()
        val unordered = fields.filterNot { mapping.native(it) }
        if (unordered.isEmpty()) {
            // A range or != on a field makes Firestore order by it, so only the sort's own fields may have one.
            val pushed = pushDown(filter, fields.toSet()) { it in fields || (it == Resource.ID && fields.isEmpty()) }
            val unknown = fields.filter { it !in pushed.nonNull && !mapping.required(it) }
            if (unknown.isEmpty()) {
                val order = keys.filter { it.field !in pushed.pinned }
                    .let { if (it.lastOrNull()?.field == Resource.ID) it else it + Sort(Resource.ID) }
                return pushed.plan(order, reasons)
            }
            reasons += "the sort on ${unknown.joinToString { "'$it'" }} is done in memory, because records without a value would be left out " +
                "(filter on the field, or make it required)"
        } else {
            reasons += "the sort on ${unordered.joinToString { "'$it'" }} is done in memory, because decimals stored as strings don't sort by value"
        }
        return pushDown(filter, emptySet()) { true }.plan(null, reasons)
    }

    /** Plans a count, which needs no order. */
    fun forCount(filter: Filter?): QueryPlan = pushDown(filter, emptySet()) { true }.plan(null, mutableListOf())

    private class PushDown {
        val conditions = mutableListOf<Condition>()
        val residual = mutableListOf<Filter>()
        /** Fields an equality fixes to one value, which needn't be ordered by. */
        val pinned = mutableSetOf<String>()
        /** Fields every record Firestore returns has a value in. */
        val nonNull = mutableSetOf<String>()
        var notEquals = 0
        var disjunctions = 1
        var nothing = false

        fun plan(order: List<Sort>?, reasons: MutableList<String>): QueryPlan {
            if (residual.isNotEmpty()) reasons += "the conditions ${residual.joinToString()} are checked in memory"
            val rest = when (residual.size) {
                0 -> null
                1 -> residual.single()
                else -> Filter.And(residual)
            }
            return QueryPlan(conditions, rest, order, nothing, reasons)
        }
    }

    /**
     * Pushes each condition of [filter] that Firestore answers exactly as BaseKt does to Firestore. A range or `!=` goes
     * only on a field [inequality] allows, an `in` never on a field in [sorted], which Firestore can't order by.
     */
    private fun pushDown(filter: Filter?, sorted: Set<String>, inequality: (String) -> Boolean): PushDown {
        val pushed = PushDown()
        for (condition in conjuncts(filter)) {
            if (!push(condition, pushed, sorted, inequality)) pushed.residual += condition
        }
        return pushed
    }

    /** The conditions that must all hold, with negations moved inward where that is exact. */
    private fun conjuncts(filter: Filter?): List<Filter> = when (filter) {
        null -> emptyList()
        is Filter.And -> filter.filters.flatMap { conjuncts(it) }
        is Filter.Not -> when (val inner = filter.filter) {
            is Filter.Not -> conjuncts(inner.filter)
            is Filter.IsNull -> listOf(inner.copy(isNull = !inner.isNull))
            is Filter.Or -> inner.filters.flatMap { conjuncts(Filter.Not(it)) }
            else -> listOf(filter)
        }
        else -> listOf(filter)
    }

    private fun push(filter: Filter, pushed: PushDown, sorted: Set<String>, inequality: (String) -> Boolean): Boolean {
        when (filter) {
            is Filter.Compare -> {
                if (!comparable(filter.field, filter.value)) return false
                val path = path(filter.field)
                val value = mapping.encode(filter.field, filter.value)
                if (filter.op == Comparison.EQ) {
                    pushed.conditions += Condition.equalTo(path, value)
                    pushed.pinned += filter.field
                } else {
                    if (!inequality(filter.field)) return false
                    if (filter.op == Comparison.NE && pushed.notEquals++ > 0) return false
                    pushed.conditions += when (filter.op) {
                        Comparison.NE -> Condition.notEqualTo(path, value)
                        Comparison.GT -> Condition.greaterThan(path, value)
                        Comparison.GTE -> Condition.greaterThanOrEqualTo(path, value)
                        Comparison.LT -> Condition.lessThan(path, value)
                        Comparison.LTE -> Condition.lessThanOrEqualTo(path, value)
                        Comparison.EQ -> error("unreachable")
                    }
                }
                pushed.nonNull += filter.field
                return true
            }
            is Filter.In -> {
                val values = filter.values.distinct()
                if (values.isEmpty()) return true.also { pushed.nothing = true }
                if (filter.field in sorted || values.any { !comparable(filter.field, it) }) return false
                if (pushed.disjunctions * values.size > MAX_DISJUNCTIONS) return false
                pushed.conditions += if (values.size == 1) Condition.equalTo(path(filter.field), mapping.encode(filter.field, values.single()))
                else Condition.inArray(path(filter.field), values.map { mapping.encode(filter.field, it) })
                pushed.disjunctions *= values.size
                pushed.nonNull += filter.field
                return true
            }
            is Filter.IsNull -> {
                if (filter.field == Resource.ID) return true.also { if (filter.isNull) pushed.nothing = true }
                if (filter.isNull || !inequality(filter.field)) return false
                if (pushed.notEquals++ > 0) return false
                pushed.conditions += Condition.notEqualTo(path(filter.field), null)
                pushed.nonNull += filter.field
                return true
            }
            is Filter.Or -> {
                if (filter.filters.isEmpty()) return true.also { pushed.nothing = true }
                // Only an "or" of equalities, which Firestore answers as BaseKt does.
                val leaves = filter.filters.map { leaf ->
                    when {
                        leaf is Filter.Compare && leaf.op == Comparison.EQ && leaf.field !in sorted && comparable(leaf.field, leaf.value) ->
                            Condition.equalTo(path(leaf.field), mapping.encode(leaf.field, leaf.value)) to 1
                        leaf is Filter.In && leaf.values.isNotEmpty() && leaf.field !in sorted && leaf.values.all { comparable(leaf.field, it) } ->
                            Condition.inArray(path(leaf.field), leaf.values.distinct().map { mapping.encode(leaf.field, it) }) to leaf.values.distinct().size
                        else -> return false
                    }
                }
                val count = leaves.sumOf { it.second }
                if (pushed.disjunctions * count > MAX_DISJUNCTIONS) return false
                pushed.conditions += Condition.or(*leaves.map { it.first }.toTypedArray())
                pushed.disjunctions *= count
                return true
            }
            is Filter.And -> return false
            is Filter.Contains, is Filter.Not -> return false
        }
    }

    /** Whether Firestore compares [field] with [value] as BaseKt does. */
    private fun comparable(field: String, value: Value): Boolean =
        if (field == Resource.ID) value is Value.Text && validDocumentId(value.value) else mapping.native(field)
}

/** The path of [field] in a query, the document id for [Resource.ID]. */
internal fun path(field: String): FieldPath = if (field == Resource.ID) FieldPath.documentId() else FieldPath.of(field)

/** Orders cursor keys by each sort key, nulls last when ascending, then by id ascending, as [Query][io.github.basekt.data.Query] says. */
internal fun keyOrder(sort: List<Sort>): Comparator<CursorKey> = Comparator { a, b ->
    for ((index, key) in sort.withIndex()) {
        val ascending = compareNullsLast(a.values[index], b.values[index])
        val result = if (key.direction == Direction.ASC) ascending else -ascending
        if (result != 0) return@Comparator result
    }
    a.id.compareTo(b.id)
}

private fun compareNullsLast(a: Value?, b: Value?): Int = when {
    a == null && b == null -> 0
    a == null -> 1
    b == null -> -1
    else -> a.compareTo(b)
}

/** Whether this record matches [filter], with [Query][io.github.basekt.data.Query]'s semantics. */
internal fun Record.matches(filter: Filter): Boolean = when (filter) {
    is Filter.Compare -> this[filter.field]?.let { value ->
        val order = value.compareTo(filter.value)
        when (filter.op) {
            Comparison.EQ -> order == 0
            Comparison.NE -> order != 0
            Comparison.GT -> order > 0
            Comparison.GTE -> order >= 0
            Comparison.LT -> order < 0
            Comparison.LTE -> order <= 0
        }
    } ?: false
    is Filter.In -> this[filter.field]?.let { it in filter.values } ?: false
    is Filter.Contains -> (this[filter.field] as Value.Text?)?.value?.contains(filter.text, ignoreCase = true) ?: false
    is Filter.IsNull -> (this[filter.field] == null) == filter.isNull
    is Filter.And -> filter.filters.all { matches(it) }
    is Filter.Or -> filter.filters.any { matches(it) }
    is Filter.Not -> !matches(filter.filter)
}
