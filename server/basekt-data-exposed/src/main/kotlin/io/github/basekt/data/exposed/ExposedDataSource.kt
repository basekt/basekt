package io.github.basekt.data.exposed

import io.github.basekt.data.Counting
import io.github.basekt.data.CursorCodec
import io.github.basekt.data.DataSource
import io.github.basekt.data.DuplicateIdException
import io.github.basekt.data.Filter
import io.github.basekt.data.Page
import io.github.basekt.data.Query
import io.github.basekt.data.checkQuery
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * A [DataSource] over Exposed and JDBC, for Postgres, MySQL, H2 and the other databases Exposed supports. Each
 * resource of [schema] is a table laid out by [mapping]; call [createTables] once to create the missing ones.
 *
 * - Every call outside [transaction] is its own database transaction, run on [dispatcher].
 * - [list] is one query: filters become `WHERE` clauses, and the cursor becomes a keyset condition on the sort
 *   keys and the id, so pages stay stable while other calls insert records.
 * - [getMany] is one `WHERE id IN (...)` query.
 * - A nested [transaction] is a savepoint.
 * - Text sorts by the database's collation. H2 compares like Kotlin strings; Postgres uses the database's locale.
 *
 * It also implements [Counting].
 *
 * @param database the Exposed database to use. The application creates and owns it.
 * @param schema the resources to store.
 * @param mapping how resources become tables.
 * @param newId generates ids for records created without one. Random UUIDs by default.
 * @param dispatcher where the blocking JDBC calls run. [Dispatchers.IO] by default.
 */
public class ExposedDataSource(
    private val database: Database,
    schema: Schema,
    mapping: TableMapping = TableMapping(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DataSource, Counting {
    private val tables: Map<String, ResourceTable>
    private val savepoints = AtomicLong()

    init {
        schema.requireValid()
        mapping.check(schema)
        tables = schema.resources.associate { it.name to ResourceTable(it, mapping) }
    }

    /**
     * Creates the tables and indexes that don't exist yet. Existing tables are left as they are: changing them as
     * the schema evolves is the application's migration tool's job.
     */
    public suspend fun createTables() {
        run { SchemaUtils.create(*tables.values.toTypedArray()) }
    }

    /** Drops every table of the schema. For tests. */
    internal suspend fun dropTables() {
        run { SchemaUtils.drop(*tables.values.toTypedArray()) }
    }

    override suspend fun get(resource: Resource, id: String): Record? = run { it.get(resource, id) }

    override suspend fun getMany(resource: Resource, ids: Collection<String>): Map<String, Record> =
        run { it.getMany(resource, ids) }

    override suspend fun list(resource: Resource, query: Query): Page = run { it.list(resource, query) }

    override suspend fun count(resource: Resource, filter: Filter?): Long = run { it.count(resource, filter) }

    override suspend fun create(resource: Resource, record: Record): Record = run { it.create(resource, record) }

    override suspend fun update(resource: Resource, id: String, changes: Record): Record? =
        run { it.update(resource, id, changes) }

    override suspend fun delete(resource: Resource, id: String): Boolean = run { it.delete(resource, id) }

    override suspend fun <T> transaction(block: suspend (DataSource) -> T): T = run { block(it) }

    private suspend fun <T> run(block: suspend (Session) -> T): T = withContext(dispatcher) {
        suspendTransaction(database) {
            // A retry would run the caller's block twice.
            maxAttempts = 1
            block(Session(this))
        }
    }

    private fun table(resource: Resource): ResourceTable =
        tables[resource.name] ?: throw IllegalArgumentException("'${resource.name}' is not in this source's schema.")

    /** The calls of one open transaction [tx]. */
    private inner class Session(private val tx: JdbcTransaction) : DataSource, Counting {
        override suspend fun get(resource: Resource, id: String): Record? {
            val table = table(resource)
            return table.selectAll().where(table.id eq id).firstOrNull()?.let(table::toRecord)
        }

        override suspend fun getMany(resource: Resource, ids: Collection<String>): Map<String, Record> {
            if (ids.isEmpty()) return emptyMap()
            val table = table(resource)
            return table.selectAll().where(table.id inList ids.distinct())
                .map(table::toRecord)
                .associateBy { (it[Resource.ID] as Value.Text).value }
        }

        override suspend fun list(resource: Resource, query: Query): Page {
            resource.checkQuery(query)
            val table = table(resource)
            val codec = CursorCodec(resource, query.sort)
            val conditions = listOfNotNull(
                query.filter?.let { predicate(table, it) },
                query.cursor?.let { keysetAfter(table, query.sort, codec.decode(it)) },
            )
            val rows = table.selectAll()
                .apply { if (conditions.isNotEmpty()) where(conditions.reduce(Op<Boolean>::and)) }
                .orderBy(*ordering(table, query.sort))
                .limit(query.limit + 1)
                .map(table::toRecord)
            val page = rows.take(query.limit)
            val next = if (rows.size > query.limit) codec.encode(codec.keyOf(page.last())) else null
            return Page(page, next)
        }

        override suspend fun count(resource: Resource, filter: Filter?): Long {
            resource.checkQuery(Query(filter))
            val table = table(resource)
            return table.selectAll().apply { filter?.let { where(predicate(table, it)) } }.count()
        }

        override suspend fun create(resource: Resource, record: Record): Record {
            val table = table(resource)
            val id = (record[Resource.ID] as Value.Text?)?.value ?: newId()
            // A failed statement aborts a Postgres transaction, so a duplicate id is caught at a savepoint.
            savepoint {
                try {
                    table.insert { row ->
                        row[table.id] = id
                        for (field in resource.fields) row[table.column(field.name)] = record[field.name]?.toColumn(field.type)
                    }
                } catch (e: SQLException) {
                    throw DuplicateIdException(resource.name, id).takeIf { e.isConstraintViolation() } ?: e
                }
            }
            return get(resource, id)!!
        }

        override suspend fun update(resource: Resource, id: String, changes: Record): Record? {
            val table = table(resource)
            val fields = resource.fields.filter { it.name in changes }
            if (fields.isNotEmpty()) {
                val updated = table.update({ table.id eq id }) { row ->
                    for (field in fields) row[table.column(field.name)] = changes[field.name]?.toColumn(field.type)
                }
                if (updated == 0) return null
            }
            return get(resource, id)
        }

        override suspend fun delete(resource: Resource, id: String): Boolean {
            val table = table(resource)
            return table.deleteWhere { table.id eq id } > 0
        }

        override suspend fun <T> transaction(block: suspend (DataSource) -> T): T = savepoint { block(this) }

        /** Runs [block] inside a savepoint, and rolls back to it if [block] throws. */
        private suspend fun <T> savepoint(block: suspend () -> T): T {
            val connection = tx.connection
            val savepoint = connection.setSavepoint("basekt_${savepoints.incrementAndGet()}")
            val result = try {
                block()
            } catch (e: Throwable) {
                connection.rollback(savepoint)
                throw e
            }
            connection.releaseSavepoint(savepoint)
            return result
        }
    }
}

/** Whether this is an integrity constraint violation (SQLSTATE class 23), such as a duplicate primary key. */
private fun SQLException.isConstraintViolation(): Boolean =
    generateSequence<Throwable>(this) { it.cause }.any { (it as? SQLException)?.sqlState?.startsWith("23") == true }
