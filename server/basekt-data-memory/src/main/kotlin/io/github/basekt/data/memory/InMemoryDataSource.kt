package io.github.basekt.data.memory

import io.github.basekt.data.AggregateFunction
import io.github.basekt.data.AggregateGroup
import io.github.basekt.data.AggregatePage
import io.github.basekt.data.AggregateQuery
import io.github.basekt.data.Aggregating
import io.github.basekt.data.Comparison
import io.github.basekt.data.ConditionalUpdate
import io.github.basekt.data.ConditionalWrites
import io.github.basekt.data.Counting
import io.github.basekt.data.CursorCodec
import io.github.basekt.data.CursorKey
import io.github.basekt.data.DataSource
import io.github.basekt.data.Direction
import io.github.basekt.data.DuplicateIdException
import io.github.basekt.data.Filter
import io.github.basekt.data.Page
import io.github.basekt.data.Query
import io.github.basekt.data.Sequencing
import io.github.basekt.data.Sort
import io.github.basekt.data.averageOf
import io.github.basekt.data.checkAggregate
import io.github.basekt.data.startOf
import io.github.basekt.data.checkConditionalUpdate
import io.github.basekt.data.checkQuery
import io.github.basekt.data.plus
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.TimeBucket
import io.github.basekt.schema.Value
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * A [DataSource] that keeps records in memory, for tests, samples and prototypes. Nothing survives the instance.
 *
 * Operations run one at a time, and a transaction holds the source until it ends. Writes in a transaction go to a
 * private copy of each table they touch, so a rollback is just dropping it. It also implements [Counting],
 * [Sequencing], whose counters roll back with the tables, [ConditionalWrites], atomic because operations take
 * turns, and [Aggregating].
 *
 * @param newId generates ids for records created without one. Random UUIDs by default.
 */
public class InMemoryDataSource(
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : DataSource, Counting, Sequencing, ConditionalWrites, Aggregating {
    private val mutex = Mutex()
    private var tables: Map<String, Map<String, Record>> = emptyMap()
    private var sequences: Map<String, Long> = emptyMap()

    override suspend fun get(resource: Resource, id: String): Record? = read { it.get(resource, id) }

    override suspend fun getMany(resource: Resource, ids: Collection<String>): Map<String, Record> =
        read { it.getMany(resource, ids) }

    override suspend fun list(resource: Resource, query: Query): Page = read { it.list(resource, query) }

    override suspend fun count(resource: Resource, filter: Filter?): Long = read { session ->
        resource.checkQuery(Query(filter))
        session.records(resource).count { record -> filter?.let { record.matches(it) } ?: true }.toLong()
    }

    override suspend fun create(resource: Resource, record: Record): Record = write { it.create(resource, record) }

    override suspend fun update(resource: Resource, id: String, changes: Record): Record? =
        write { it.update(resource, id, changes) }

    override suspend fun delete(resource: Resource, id: String): Boolean = write { it.delete(resource, id) }

    override suspend fun <T> transaction(block: suspend (DataSource) -> T): T = write { block(it) }

    override suspend fun nextValue(name: String, start: Long): Long = write { it.nextValue(name, start) }

    override suspend fun aggregate(resource: Resource, query: AggregateQuery): AggregatePage = read { session ->
        resource.checkAggregate(query)
        val matching = session.records(resource).filter { record -> query.filter?.let { record.matches(it) } ?: true }
        val grouped = matching.groupBy { record -> query.groupBy.map { field -> record[field].bucketed(query.buckets[field]) } }
        // Without group fields there is always one group, as in SQL, even of no records.
        val groups = if (query.groupBy.isEmpty() && grouped.isEmpty()) mapOf(emptyList<Value?>() to emptyList()) else grouped
        val all = groups.map { (key, records) ->
            AggregateGroup(query.groupBy.zip(key).toMap(), query.measures.associate { it.name to it.function.over(it.field, records) })
        }.sortedWith(groupOrder(query))
        AggregatePage(all.take(query.limit), all.size > query.limit)
    }

    override suspend fun updateIf(
        resource: Resource,
        id: String,
        condition: Filter?,
        changes: Record,
        increments: Map<String, Value>,
    ): ConditionalUpdate = write { it.updateIf(resource, id, condition, changes, increments) }

    private suspend fun <T> read(block: suspend (Session) -> T): T = mutex.withLock { block(Session(tables, sequences)) }

    private suspend fun <T> write(block: suspend (Session) -> T): T = mutex.withLock {
        val session = Session(tables, sequences)
        val result = block(session)
        tables = session.snapshot()
        sequences = session.counters
        result
    }

    /** A view of [base] that copies a table the first time it writes to it, and its own copy of the counters. */
    private inner class Session(
        private val base: Map<String, Map<String, Record>>,
        var counters: Map<String, Long>,
    ) : DataSource, Sequencing, ConditionalWrites {
        private val written = mutableMapOf<String, LinkedHashMap<String, Record>>()

        fun snapshot(): Map<String, Map<String, Record>> = base + written

        private fun table(resource: Resource): Map<String, Record> =
            written[resource.name] ?: base[resource.name].orEmpty()

        fun records(resource: Resource): Collection<Record> = table(resource).values

        private fun writable(resource: Resource): LinkedHashMap<String, Record> =
            written.getOrPut(resource.name) { LinkedHashMap(base[resource.name].orEmpty()) }

        override suspend fun get(resource: Resource, id: String): Record? = table(resource)[id]

        override suspend fun getMany(resource: Resource, ids: Collection<String>): Map<String, Record> {
            val table = table(resource)
            return ids.mapNotNull { id -> table[id]?.let { id to it } }.toMap()
        }

        override suspend fun list(resource: Resource, query: Query): Page {
            resource.checkQuery(query)
            val sort = query.sort
            val codec = CursorCodec(resource, sort)
            val order = keyOrder(sort)
            val after = query.cursor?.let(codec::decode)
            val matching = table(resource).values
                .filter { record -> query.filter?.let { record.matches(it) } ?: true }
                .map { codec.keyOf(it) to it }
                .sortedWith { a, b -> order.compare(a.first, b.first) }
                .filter { (key, _) -> after == null || order.compare(key, after) > 0 }
            val page = matching.take(query.limit)
            val next = if (matching.size > query.limit) codec.encode(page.last().first) else null
            return Page(page.map { it.second }, next)
        }

        override suspend fun create(resource: Resource, record: Record): Record {
            val id = (record[Resource.ID] as Value.Text?)?.value ?: newId()
            val table = writable(resource)
            if (id in table) throw DuplicateIdException(resource.name, id)
            val stored = buildMap {
                put(Resource.ID, Value.Text(id))
                for (field in resource.fields) put(field.name, record[field.name])
            }
            table[id] = stored
            return stored
        }

        override suspend fun update(resource: Resource, id: String, changes: Record): Record? {
            val current = table(resource)[id] ?: return null
            val updated = current + changes.filterKeys { it != Resource.ID }
            writable(resource)[id] = updated
            return updated
        }

        override suspend fun delete(resource: Resource, id: String): Boolean {
            if (id !in table(resource)) return false
            writable(resource).remove(id)
            return true
        }

        override suspend fun <T> transaction(block: suspend (DataSource) -> T): T {
            val inner = Session(snapshot(), counters)
            val result = block(inner)
            for ((name, table) in inner.written) written[name] = table
            counters = inner.counters
            return result
        }

        override suspend fun updateIf(
            resource: Resource,
            id: String,
            condition: Filter?,
            changes: Record,
            increments: Map<String, Value>,
        ): ConditionalUpdate {
            resource.checkConditionalUpdate(condition, changes, increments)
            val current = table(resource)[id] ?: return ConditionalUpdate.NotFound
            if (condition != null && !current.matches(condition)) return ConditionalUpdate.ConditionFailed
            val added = increments.mapValues { (name, amount) -> current[name]?.plus(amount) }
            return ConditionalUpdate.Updated(update(resource, id, changes + added)!!)
        }

        override suspend fun nextValue(name: String, start: Long): Long {
            val next = counters[name]?.plus(1) ?: start
            counters = counters + (name to next)
            return next
        }
    }
}

private fun AggregateFunction.over(field: String?, records: List<Record>): Value? {
    val values = if (field == null) emptyList() else records.mapNotNull { it[field] }
    return when (this) {
        AggregateFunction.COUNT -> Value.Integer(if (field == null) records.size.toLong() else values.size.toLong())
        AggregateFunction.SUM -> values.reduceOrNull { total, value -> total + value }
        AggregateFunction.AVG -> averageOf(values.reduceOrNull { total, value -> total + value }, values.size.toLong())
        AggregateFunction.MIN -> values.minOrNull()
        AggregateFunction.MAX -> values.maxOrNull()
    }
}

/** Orders groups by the query's sort keys, then by every group field ascending, nulls after values when ascending. */
private fun groupOrder(query: AggregateQuery): Comparator<AggregateGroup> = Comparator { a, b ->
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

/** Orders cursor keys by each sort key, nulls last when ascending, then by id ascending. */
private fun keyOrder(sort: List<Sort>): Comparator<CursorKey> = Comparator { a, b ->
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

private fun Record.matches(filter: Filter): Boolean = when (filter) {
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

/** This value in its [bucket]'s start, or as it is without one. */
private fun Value?.bucketed(bucket: TimeBucket?): Value? =
    if (bucket == null || this !is Value.Timestamp) this else Value.Timestamp(startOf(bucket, value))
