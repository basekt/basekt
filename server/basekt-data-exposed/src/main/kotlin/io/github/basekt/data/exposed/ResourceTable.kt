@file:OptIn(ExperimentalUuidApi::class)

package io.github.basekt.data.exposed

import io.github.basekt.data.DataException
import io.github.basekt.data.InvalidQueryException
import io.github.basekt.schema.Field
import io.github.basekt.schema.FieldType
import io.github.basekt.schema.IdSpec
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Rule
import io.github.basekt.schema.Value
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.statements.api.RowApi
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The table of one [resource]: laid out by [TableMapping], or, when the mapping names it in [TableMapping.tables], the
 * existing table its [MappedTable] describes.
 */
internal class ResourceTable(val resource: Resource, mapping: TableMapping) : Table(mapping.tableOf(resource.name)) {
    /** The existing table this resource is kept in, or `null` when BaseKt lays the table out. */
    val mapped: MappedTable? = mapping.tables[resource.name]

    /** Whether writes are refused. */
    val readOnly: Boolean = mapped?.readOnly == true

    private val fieldColumns: Map<String, Column<Any?>>
    private val stored: Map<String, Stored>

    /** The columns of the primary key: the id's, or the key fields' in order. */
    val keyColumns: List<Column<Any?>>

    /** How each of [keyColumns] keeps its values. */
    val keyStored: List<Stored>

    /** Whether the id is made of several key fields' columns, as [IdSpec.keyOf] makes it. */
    val composite: Boolean

    /** Whether the database makes the key of each new row. */
    val generated: Boolean

    override val primaryKey: PrimaryKey

    init {
        if (mapped == null) {
            stored = resource.fields.associate { it.name to Stored(it.type, SqlType.defaultFor(it.type)) }
            fieldColumns = resource.fields.associate { it.name to ownColumn(it, mapping) }
            @Suppress("UNCHECKED_CAST")
            keyColumns = listOf(varchar(Resource.ID, mapping.idLength) as Column<Any?>)
            keyStored = listOf(Stored(FieldType.STRING, SqlType.TEXT))
            composite = false
            generated = false
        } else {
            stored = resource.fields.associate { it.name to Stored(it.type, mapped.typeOf(it.name, it.type)) }
            fieldColumns = resource.fields.associate { it.name to mappedColumn(mapped.columnOf(it.name), stored.getValue(it.name).sql, nullable = true) }
            when (val key = mapped.keyOf(resource)) {
                is MappedKey.Column -> {
                    val column = mappedColumn(key.name, key.type, nullable = false)
                    @Suppress("UNCHECKED_CAST")
                    keyColumns = listOf((if (key.generated) (column as Column<Long>).autoIncrement() else column) as Column<Any?>)
                    keyStored = listOf(Stored(FieldType.STRING, key.type))
                    composite = false
                    generated = key.generated
                }
                MappedKey.Fields -> {
                    keyColumns = resource.id.key.map(fieldColumns::getValue)
                    keyStored = resource.id.key.map(stored::getValue)
                    composite = true
                    generated = false
                }
            }
        }
        primaryKey = PrimaryKey(keyColumns.first(), *keyColumns.drop(1).toTypedArray())
        // A mapped table is never changed, so it gets none of the indexes BaseKt would make.
        if (mapped == null) {
            val id = keyColumns.single()
            for (field in resource.fields.filter { it.type == FieldType.REFERENCE }) index(false, column(field.name), id)
            // The service checks uniqueness in the write's transaction; the index keeps two racing writers from both winning.
            for (field in resource.fields.filter { it.unique }) uniqueIndex(column(field.name))
            for (spec in mapping.indexes.filter { it.resource == resource.name }) {
                index(false, *spec.fields.map(::column).toTypedArray(), id)
            }
        }
    }

    /** Returns the column of [field], which may be [Resource.ID] when one column holds the id. */
    fun column(field: String): Column<Any?> = when {
        field != Resource.ID -> fieldColumns.getValue(field)
        composite -> throw InvalidQueryException("The id of '${resource.name}' is made of several columns, so it can't be used here.")
        else -> keyColumns.single()
    }

    /** Returns how the column of [field], which may be [Resource.ID] when one column holds the id, keeps its values. */
    fun stored(field: String): Stored = if (field == Resource.ID) keyStored.single() else stored.getValue(field)

    /** Returns the condition that matches the row with [id], or `null` when no row can have it. */
    fun idIs(id: String): Op<Boolean>? {
        val parts = if (composite) IdSpec.partsOf(id) else listOf(id)
        if (parts.size != keyColumns.size) return null
        val values = parts.zip(keyStored).map { (part, stored) -> stored.fromText(part) ?: return null }
        return keyColumns.zip(values).map { (column, value) -> column eq value }.reduce(Op<Boolean>::and)
    }

    /** Returns the condition that matches the rows with any of [ids]. */
    fun idIn(ids: Collection<String>): Op<Boolean> {
        if (!composite) {
            val values = ids.mapNotNull { keyStored.single().fromText(it) }.distinct()
            return if (values.isEmpty()) Op.FALSE else keyColumns.single() inList values
        }
        return ids.distinct().mapNotNull(::idIs).reduceOrNull(Op<Boolean>::or) ?: Op.FALSE
    }

    /** Returns the id of [row]. */
    fun idOf(row: ResultRow): String {
        val parts = keyColumns.zip(keyStored).map { (column, stored) -> stored.text(row[column]!!) }
        return if (composite) IdSpec.keyOf(parts) else parts.single()
    }

    /** Sets the key of a new row to [id], unless the key fields' columns hold it or the database makes it. */
    fun setId(row: UpdateBuilder<*>, id: String) {
        if (composite || generated) return
        row[keyColumns.single()] = keyStored.single().fromText(id)
            ?: throw DataException("'${resource.name}' keeps its ids in ${keyStored.single().sql.phrase} column, so '$id' can't be one.")
    }

    /** Sets [field]'s column of a row to [value]. */
    fun set(row: UpdateBuilder<*>, field: String, value: Value?) {
        row[column(field)] = value?.let { stored(field).toColumn(it) ?: throw unfit(field, it) }
    }

    /** Reads a row as a record holding the id and every field. */
    fun toRecord(row: ResultRow): Record = buildMap {
        put(Resource.ID, Value.Text(idOf(row)))
        for (field in resource.fields) put(field.name, row[fieldColumns.getValue(field.name)]?.let { stored.getValue(field.name).fromColumn(it) })
    }

    private fun unfit(field: String, value: Value) =
        DataException("'${resource.name}.$field' is kept in ${stored(field).sql.phrase} column, which can't hold '$value'.")

    @Suppress("UNCHECKED_CAST")
    private fun ownColumn(field: Field, mapping: TableMapping): Column<Any?> {
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

    /** A column of an existing table, typed as [type] for reading and writing. Its size doesn't matter there. */
    private fun mappedColumn(name: String, type: SqlType, nullable: Boolean): Column<Any?> = when (type) {
        SqlType.TEXT -> text(name).nullable(nullable)
        SqlType.INTEGER -> integer(name).nullable(nullable)
        SqlType.BIGINT -> long(name).nullable(nullable)
        // Exposed scales what it writes to the column's scale, so the widest one keeps every digit; the database rounds.
        SqlType.DECIMAL -> decimal(name, 65, 30).nullable(nullable)
        SqlType.BOOLEAN -> bool(name).nullable(nullable)
        SqlType.TIMESTAMP -> registerColumn<Instant>(name, UtcTimestampColumnType()).nullable(nullable)
        SqlType.TIMESTAMPTZ -> registerColumn<Instant>(name, InstantColumnType()).nullable(nullable)
        SqlType.UUID -> uuid(name).nullable(nullable)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> Column<T>.nullable(nullable: Boolean): Column<Any?> = (if (nullable) nullable() else this) as Column<Any?>
}

/** The longest file key, as `basekt-storage`'s BlobKey allows. */
private const val FILE_KEY_LENGTH = 1024

private fun Field.maxLength(): Int? = rules.filterIsInstance<Rule.Length>().mapNotNull { it.max }.minOrNull()

/**
 * How a column of type [sql] keeps the values of a field of [type]: as the field's own, or, for text kept in a number
 * or UUID column, as the number or UUID the text holds.
 */
internal class Stored(private val type: FieldType, val sql: SqlType) {
    /** Whether the column holds text, so it can be searched. */
    val isText: Boolean get() = sql == SqlType.TEXT

    /** Converts [value] to what the column stores, or returns `null` when the column can't hold it. */
    fun toColumn(value: Value): Any? = when (value) {
        is Value.Text -> fromText(value.value)
        is Value.Integer -> if (sql == SqlType.INTEGER) Math.toIntExact(value.value) else value.value
        is Value.Decimal -> BigDecimal(value.literal)
        is Value.Bool -> value.value
        is Value.Timestamp -> value.value
        is Value.Items -> throw IllegalArgumentException("A list is an action input, so it has no column.")
    }

    /** Converts [text] to what the column stores, or returns `null` when it isn't a value the column holds. */
    fun fromText(text: String): Any? = when (sql) {
        // Only the number's own spelling, so the id read back is the one that was given.
        SqlType.INTEGER -> text.toIntOrNull()?.takeIf { it.toString() == text }
        SqlType.BIGINT -> text.toLongOrNull()?.takeIf { it.toString() == text }
        SqlType.UUID -> runCatching { Uuid.parse(text) }.getOrNull()?.takeIf { it.toString() == text }
        else -> text
    }

    /** The text a stored id or key part is. */
    fun text(stored: Any): String = when (val value = fromColumn(stored)) {
        is Value.Text -> value.value
        is Value.Integer -> value.value.toString()
        else -> error("A key can't be ${type.name.lowercase()}.")
    }

    /** Converts what the column stores back to a value of the field. */
    fun fromColumn(stored: Any): Value = when (type) {
        FieldType.STRING, FieldType.ENUM, FieldType.REFERENCE, FieldType.FILE -> Value.Text(stored.toString())
        FieldType.INT, FieldType.LONG -> Value.Integer((stored as Number).toLong())
        FieldType.DECIMAL -> Value.Decimal.of((stored as BigDecimal).stripTrailingZeros().toPlainString())
        FieldType.BOOL -> Value.Bool(stored as Boolean)
        FieldType.INSTANT -> Value.Timestamp(stored as Instant)
    }
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

/** A `TIMESTAMP` column without a time zone, holding [Instant]s as UTC times. */
internal class UtcTimestampColumnType : ColumnType<Instant>() {
    override fun sqlType(): String = currentDialect.dataTypeProvider.dateTimeType()

    override fun valueFromDB(value: Any): Instant = when (value) {
        is Instant -> value
        is LocalDateTime -> value.toInstant(ZoneOffset.UTC).toKotlinInstant()
        is java.sql.Timestamp -> value.toLocalDateTime().toInstant(ZoneOffset.UTC).toKotlinInstant()
        is OffsetDateTime -> value.toInstant().toKotlinInstant()
        else -> error("Can't read an instant from ${value::class.qualifiedName}.")
    }

    override fun notNullValueToDB(value: Instant): Any = LocalDateTime.ofInstant(value.toJavaInstant(), ZoneOffset.UTC)

    override fun readObject(rs: RowApi, index: Int): Any? = rs.getObject(index, LocalDateTime::class.java)
}
