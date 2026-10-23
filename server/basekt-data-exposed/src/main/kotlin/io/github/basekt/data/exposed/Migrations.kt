package io.github.basekt.data.exposed

import org.jetbrains.exposed.v1.core.ColumnDiff
import org.jetbrains.exposed.v1.core.Index
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata

/** What [ExposedDataSource.migrate] changed in the database, and what it found but left alone. */
public data class MigrationReport(
    /** The SQL statements it ran, in order. Empty when the database already matched the schema. */
    val applied: List<String>,
    /**
     * Differences it left alone, because changing them could lose data: a column no field has any more, or a column
     * whose type differs from its field's. Each is a sentence naming the table and column.
     */
    val notes: List<String>,
)

/**
 * Brings the database up to [tables] without losing data: it creates the tables that are missing, adds the missing
 * columns (every field's column is nullable, so existing rows get null), widens text columns that became too short
 * for their field, such as an enum given a longer value, and creates the missing indexes. It never drops, renames or
 * narrows anything, nor changes a column's type: those it lists in [MigrationReport.notes] for a person to decide.
 */
internal fun JdbcTransaction.migrate(tables: List<Table>): MigrationReport {
    val applied = mutableListOf<String>()
    val notes = mutableListOf<String>()
    fun run(statements: List<String>) {
        for (sql in statements) {
            exec(sql)
            applied += sql
        }
    }

    val metadata = currentDialectMetadata
    // Exposed keeps what it read of the database per connection pool; another call may have changed it since.
    metadata.resetCaches()
    val missing = tables.filterNot { metadata.tableExists(it) }
    if (missing.isNotEmpty()) run(SchemaUtils.createStatements(*missing.toTypedArray()))
    val existing = tables - missing.toSet()
    if (existing.isEmpty()) return MigrationReport(applied, notes)

    val columns = metadata.tableColumns(*existing.toTypedArray())
    val indices = metadata.existingIndices(*existing.toTypedArray())
    for (table in existing) {
        val found = columns[table].orEmpty().associateBy { it.name.lowercase() }
        for (column in table.columns) {
            val have = found[column.name.lowercase()]
            val length = (column.columnType as? VarCharColumnType)?.colLength
            when {
                have == null -> run(column.createStatement())
                length != null && have.size != null && have.size!! < length -> run(column.modifyStatements(sizeOnly))
                length != null && have.size != null && have.size!! > length -> Unit // Wider than needed holds every value.
            }
        }
        val known = table.columns.map { it.name.lowercase() }.toSet()
        for (name in found.keys - known) {
            notes += "${table.tableName}.${found.getValue(name).name} has no field any more; it was left in place with its data."
        }
        val present = indices[table].orEmpty()
        for (index in table.indices) {
            if (present.none { it.sameAs(index) }) run(SchemaUtils.createIndex(index))
        }
    }
    return MigrationReport(applied, notes)
}

/** Changes a column's size and nothing else. */
private val sizeOnly = ColumnDiff(
    nullability = false,
    type = false,
    autoInc = false,
    defaults = false,
    caseSensitiveName = false,
    sizeAndScale = true,
    comment = false,
)

/** Whether this existing index covers the same columns, as uniquely, as [wanted], whatever its name. */
private fun Index.sameAs(wanted: Index): Boolean =
    unique == wanted.unique && columns.map { it.name.lowercase() } == wanted.columns.map { it.name.lowercase() }
