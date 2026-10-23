package io.github.basekt.data.exposed

import io.github.basekt.schema.Field
import io.github.basekt.schema.FieldType
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Rule
import io.github.basekt.schema.Value
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.statements.api.RowApi
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/** The table of one [resource], laid out by [TableMapping]. */
internal class ResourceTable(val resource: Resource, mapping: TableMapping) : Table(mapping.tablePrefix + resource.name) {
    val id: Column<String> = varchar(Resource.ID, mapping.idLength)
    override val primaryKey: PrimaryKey = PrimaryKey(id)

    private val fieldColumns: Map<String, Column<Any?>> = resource.fields.associate { it.name to column(it, mapping) }

    init {
        for (field in resource.fields.filter { it.type == FieldType.REFERENCE }) index(false, column(field.name), id)
        for (spec in mapping.indexes.filter { it.resource == resource.name }) {
            index(false, *spec.fields.map(::column).toTypedArray(), id)
        }
    }

    /** Returns the column of [field], which may be [Resource.ID]. */
    @Suppress("UNCHECKED_CAST")
    fun column(field: String): Column<Any?> =
        if (field == Resource.ID) id as Column<Any?> else fieldColumns.getValue(field)

    /** Reads a row as a record holding the id and every field. */
    fun toRecord(row: ResultRow): Record = buildMap {
        put(Resource.ID, Value.Text(row[id]))
        for (field in resource.fields) put(field.name, row[fieldColumns.getValue(field.name)]?.let { fromColumn(field.type, it) })
    }

    @Suppress("UNCHECKED_CAST")
    private fun column(field: Field, mapping: TableMapping): Column<Any?> {
        val name = field.name
        val column = when (field.type) {
            FieldType.STRING -> field.maxLength()?.let { varchar(name, it).nullable() } ?: text(name).nullable()
            FieldType.ENUM -> varchar(name, field.values.orEmpty().maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1).nullable()
            FieldType.REFERENCE -> varchar(name, mapping.idLength).nullable()
            FieldType.FILE -> varchar(name, FILE_KEY_LENGTH).nullable()
            FieldType.INT -> integer(name).nullable()
            FieldType.LONG -> long(name).nullable()
            FieldType.DECIMAL -> decimal(name, mapping.decimalPrecision, mapping.decimalScale).nullable()
            FieldType.BOOL -> bool(name).nullable()
            FieldType.INSTANT -> registerColumn<Instant>(name, InstantColumnType()).nullable()
        }
        return column as Column<Any?>
    }
}

/** The longest file key, as `basekt-storage`'s BlobKey allows. */
private const val FILE_KEY_LENGTH = 1024

private fun Field.maxLength(): Int? = rules.filterIsInstance<Rule.Length>().mapNotNull { it.max }.minOrNull()

/** Converts a value of a field of [type] to what its column stores. */
internal fun Value.toColumn(type: FieldType): Any = when (this) {
    is Value.Text -> value
    is Value.Integer -> if (type == FieldType.INT) Math.toIntExact(value) else value
    is Value.Decimal -> BigDecimal(literal)
    is Value.Bool -> value
    is Value.Timestamp -> value
}

internal fun fromColumn(type: FieldType, stored: Any): Value = when (type) {
    FieldType.STRING, FieldType.ENUM, FieldType.REFERENCE, FieldType.FILE -> Value.Text(stored as String)
    FieldType.INT, FieldType.LONG -> Value.Integer((stored as Number).toLong())
    FieldType.DECIMAL -> Value.Decimal.of((stored as BigDecimal).stripTrailingZeros().toPlainString())
    FieldType.BOOL -> Value.Bool(stored as Boolean)
    FieldType.INSTANT -> Value.Timestamp(stored as Instant)
}

/** A `TIMESTAMP WITH TIME ZONE` column of [Instant]s, written in UTC. */
internal class InstantColumnType : ColumnType<Instant>() {
    override fun sqlType(): String = currentDialect.dataTypeProvider.timestampWithTimeZoneType()

    override fun valueFromDB(value: Any): Instant = when (value) {
        is Instant -> value
        is OffsetDateTime -> value.toInstant().toKotlinInstant()
        is java.time.Instant -> value.toKotlinInstant()
        is java.sql.Timestamp -> value.toInstant().toKotlinInstant()
        is LocalDateTime -> value.toInstant(ZoneOffset.UTC).toKotlinInstant()
        else -> error("Can't read an instant from ${value::class.qualifiedName}.")
    }

    override fun notNullValueToDB(value: Instant): Any = OffsetDateTime.ofInstant(value.toJavaInstant(), ZoneOffset.UTC)

    override fun readObject(rs: RowApi, index: Int): Any? = rs.getObject(index, OffsetDateTime::class.java)
}
