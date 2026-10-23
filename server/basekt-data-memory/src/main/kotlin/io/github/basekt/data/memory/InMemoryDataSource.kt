package io.github.basekt.data.memory

import io.github.basekt.data.Comparison
import io.github.basekt.data.CursorCodec
import io.github.basekt.data.CursorKey
import io.github.basekt.data.DataSource
import io.github.basekt.data.Direction
import io.github.basekt.data.DuplicateIdException
import io.github.basekt.data.Filter
import io.github.basekt.data.Page
import io.github.basekt.data.Query
import io.github.basekt.data.Sort
import io.github.basekt.data.checkQuery
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * A [DataSource] that keeps records in memory, for tests, samples and prototypes. Nothing survives the instance.
 *
 * Operations run one at a time, and a transaction holds the source until it ends. Writes in a transaction go to a
 * private copy of each table they touch, so a rollback is just dropping it.
 *
 * @param newId generates ids for records created without one. Random UUIDs by default.
 */
public class InMemoryDataSource(
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : DataSource {
    private val mutex = Mutex()
    private var tables: Map<String, Map<String, Record>> = emptyMap()

    override suspend fun get(resource: Resource, id: String): Record? = read { it.get(resource, id) }

    override suspend fun getMany(resource: Resource, ids: Collection<String>): Map<String, Record> =
        read { it.getMany(resource, ids) }

    override suspend fun list(resource: Resource, query: Query): Page = read { it.list(resource, query) }

    override suspend fun create(resource: Resource, record: Record): Record = write { it.create(resource, record) }

    override suspend fun update(resource: Resource, id: String, changes: Record): Record? =
        write { it.update(resource, id, changes) }

    override suspend fun delete(resource: Resource, id: String): Boolean = write { it.delete(resource, id) }

    override suspend fun <T> transaction(block: suspend (DataSource) -> T): T = write { block(it) }

    private suspend fun <T> read(block: suspend (Session) -> T): T = mutex.withLock { block(Session(tables)) }

    private suspend fun <T> write(block: suspend (Session) -> T): T = mutex.withLock {
        val session = Session(tables)
        val result = block(session)
        tables = session.snapshot()
        result
    }

    /** A view of [base] that copies a table the first time it writes to it. */
    private inner class Session(private val base: Map<String, Map<String, Record>>) : DataSource {
        private val written = mutableMapOf<String, LinkedHashMap<String, Record>>()

        fun snapshot(): Map<String, Map<String, Record>> = base + written

        private fun table(resource: Resource): Map<String, Record> =
            written[resource.name] ?: base[resource.name].orEmpty()

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
            val inner = Session(snapshot())
            val result = block(inner)
            for ((name, table) in inner.written) written[name] = table
            return result
        }
    }
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
