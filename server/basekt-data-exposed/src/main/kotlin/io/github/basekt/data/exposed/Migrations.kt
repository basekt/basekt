package io.github.basekt.data.exposed

import io.github.basekt.schema.Resource
import org.jetbrains.exposed.v1.core.ColumnDiff
import org.jetbrains.exposed.v1.core.Index
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.vendors.ColumnMetadata
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import java.sql.Types

/** What [ExposedDataSource.migrate] changed in the database, and what it found but left alone. */
public data class MigrationReport(
    /** The SQL statements it ran, in order. Empty when the database already matched the schema. */
    val applied: List<String>,
    /**
     * Differences it left alone, because changing them could lose data: a column no field has any more, or a column
     * whose type differs from its field's; and every way a [MappedTable] doesn't match the schema. Each is a sentence
     * naming the table and column.
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

/**
 * Compares each of [tables], whose resources the mapping keeps in existing tables, with what the database has, and
 * returns what doesn't match, one sentence each. It changes nothing.
 */
internal fun JdbcTransaction.compareMapped(tables: List<ResourceTable>): List<String> {
    val notes = mutableListOf<String>()
    val metadata = currentDialectMetadata
    metadata.resetCaches()
    val existing = tables.filter { metadata.tableExists(it) }
    for (table in tables - existing.toSet()) {
        notes += "The table '${table.tableName}' that '${table.resource.name}' is mapped to doesn't exist."
    }
    if (existing.isEmpty()) return notes

    val columns = metadata.tableColumns(*existing.toTypedArray())
    val keys = metadata.existingPrimaryKeys(*existing.toTypedArray())
    for (table in existing) {
        val name = table.tableName
        val resource = table.resource.name
        val found = columns[table].orEmpty().associateBy { it.name.lowercase() }
        val wantedKey = table.keyColumns.map { it.name.lowercase() }
        val key = keys[table]?.columnNames?.map { it.lowercase() }
        if (key != null && key != wantedKey) {
            notes += "$name's primary key is (${key.joinToString()}), but the mapping keys '$resource' by (${wantedKey.joinToString()})."
        }
        if (!table.composite) {
            val column = table.keyColumns.single()
            val have = found[column.name.lowercase()]
            val type = table.stored(Resource.ID).sql
            when {
                have == null -> notes += "$name has no column '${column.name}' for the id of '$resource'."
                !type.reads(have) -> notes += "$name.${have.name} is ${have.sqlType}, which can't be read as the $type key of '$resource'."
            }
        }
        for (field in table.resource.fields) {
            val column = table.column(field.name)
            val have = found[column.name.lowercase()]
            val type = table.stored(field.name).sql
            val label = "'$resource.${field.name}'"
            when {
                have == null -> notes += "$name has no column '${column.name}' for $label."
                !type.reads(have) -> notes += "$name.${have.name} is ${have.sqlType}, which can't be read as the $type column of $label."
                field.required && have.nullable ->
                    notes += "$name.${have.name} can be null, but $label is required: a row with null there is read without it."
                !field.required && !have.nullable && have.defaultDbValue == null && !table.readOnly && column !in table.keyColumns ->
                    notes += "$name.${have.name} can't be null and has no default, but $label is optional: a create without it fails."
            }
        }
    }
    return notes
}

/** Whether a column the database describes as [column] can be read and written as this type. */
private fun SqlType.reads(column: ColumnMetadata): Boolean {
    val name = column.sqlType.lowercase()
    val zoned = "tz" in name || "time zone" in name
    return when (this) {
        SqlType.TEXT -> column.jdbcType in TEXT_TYPES && "uuid" !in name
        SqlType.INTEGER -> column.jdbcType in INTEGER_TYPES
        SqlType.BIGINT -> column.jdbcType in INTEGER_TYPES || column.jdbcType == Types.BIGINT
        SqlType.DECIMAL -> column.jdbcType == Types.DECIMAL || column.jdbcType == Types.NUMERIC
        SqlType.BOOLEAN -> column.jdbcType == Types.BOOLEAN || column.jdbcType == Types.BIT
        SqlType.TIMESTAMP -> column.jdbcType == Types.TIMESTAMP && !zoned
        SqlType.TIMESTAMPTZ -> column.jdbcType == Types.TIMESTAMP_WITH_TIMEZONE || (column.jdbcType == Types.TIMESTAMP && zoned)
        SqlType.UUID -> "uuid" in name
    }
}

private val TEXT_TYPES = setOf(
    Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB,
)

private val INTEGER_TYPES = setOf(Types.TINYINT, Types.SMALLINT, Types.INTEGER)
