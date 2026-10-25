package io.github.basekt.data.exposed

import io.github.basekt.schema.FieldType
import io.github.basekt.schema.IdSource
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema

/**
 * How [ExposedDataSource] lays a schema out as tables. Each resource becomes one table named [tablePrefix] plus the
 * resource's name, with:
 * - an `id` primary key, `VARCHAR(`[idLength]`)`;
 * - one nullable column per field: `TEXT` for strings, or `VARCHAR(n)` when a length rule caps them at `n`;
 *   `VARCHAR` for enums and references; `INTEGER`, `BIGINT`, `DECIMAL(`[decimalPrecision]`, `[decimalScale]`)`,
 *   `BOOLEAN`, and `TIMESTAMP WITH TIME ZONE` for instants;
 * - an index on `(reference, id)` for every reference field, which serves relation lookups;
 * - an index on `(fields..., id)` for each of [indexes], for the filters and sorts the application uses most.
 *   Ending with `id` lets the index serve keyset pagination too.
 *
 * Columns are nullable even for required fields: the service validates records, not the database. Reference fields
 * get no foreign-key constraint, for the same reason, so every adapter accepts the same writes.
 *
 * A resource named in [tables] is instead read from, and written to, a table that already exists, laid out as its
 * [MappedTable] says. BaseKt never creates or changes such a table.
 */
public data class TableMapping(
    /** Put before every table name, to share a database with other tables. Empty by default. */
    val tablePrefix: String = "",
    /** The length of id and reference columns. */
    val idLength: Int = 64,
    /** The total digits of decimal columns. */
    val decimalPrecision: Int = 38,
    /** The digits after the point of decimal columns. Values with more are rounded by the database. */
    val decimalScale: Int = 12,
    /** Extra indexes, each on some fields of one resource. */
    val indexes: List<IndexSpec> = emptyList(),
    /** Resources kept in tables that already exist: resource name to its table. [tablePrefix] isn't added to them. */
    val tables: Map<String, MappedTable> = emptyMap(),
) {
    init {
        require(idLength >= 1) { "idLength must be at least 1, not $idLength." }
        require(decimalPrecision >= 1 && decimalScale in 0..decimalPrecision) {
            "Decimal columns need 1 <= precision and 0 <= scale <= precision, not ($decimalPrecision, $decimalScale)."
        }
    }

    /** The name of [resource]'s table. */
    internal fun tableOf(resource: String): String = tables[resource]?.table ?: (tablePrefix + resource)

    /** Returns every way this mapping doesn't fit [schema], one sentence each; empty when it fits. */
    internal fun problems(schema: Schema, sequencesTable: String): List<String> = buildList {
        for (index in indexes) {
            val resource = schema.resource(index.resource)
            if (resource == null) {
                add("An index names the resource '${index.resource}', which the schema doesn't have.")
                continue
            }
            for (field in index.fields) {
                if (field != Resource.ID && resource.field(field) == null) {
                    add("An index on '${index.resource}' names the field '$field', which it doesn't have.")
                }
            }
            if (index.resource in tables) {
                add("An index on '${index.resource}' can't be made: its table is mapped, and BaseKt never changes a mapped table.")
            }
        }
        for ((name, table) in tables) {
            val resource = schema.resource(name)
            if (resource == null) add("The mapping names the resource '$name', which the schema doesn't have.")
            else addAll(table.problems(resource))
        }
        val names = schema.resources.groupBy { tableOf(it.name).lowercase() }
        for ((table, sharing) in names.filterValues { it.size > 1 }) {
            add("The resources ${sharing.joinToString(" and ") { "'${it.name}'" }} share the table '$table'.")
        }
        if (sequencesTable.lowercase() in names) {
            add("A resource's table can't be called '$sequencesTable': the sequences are kept there.")
        }
    }
}

/** An index on [fields] of [resource], in order. [TableMapping] appends the id. */
public data class IndexSpec(
    /** The resource's name. */
    val resource: String,
    /** The fields, most significant first. */
    val fields: List<String>,
) {
    init {
        require(fields.isNotEmpty()) { "An index on '$resource' needs at least one field." }
    }

    /** Creates an index on [fields] of [resource]. */
    public constructor(resource: String, vararg fields: String) : this(resource, fields.toList())
}

/**
 * A table that already exists, such as one another program owns, that holds one resource's records. BaseKt reads and
 * writes its rows but never creates, alters or drops it: [ExposedDataSource.migrate] reports in
 * [MigrationReport.notes] what doesn't match the schema instead.
 *
 * Columns of the table that no field names are never read, and a create leaves them to their defaults.
 */
public data class MappedTable(
    /** The table's name, as SQL would name it. It may be qualified with its schema, as in `other.entries`. */
    val table: String,
    /** The columns of fields that aren't called after the field: field name to column name. */
    val columns: Map<String, String> = emptyMap(),
    /**
     * The columns whose type isn't the one BaseKt would have made for the field: field name to type. See [SqlType]
     * for the pairings each field type can have.
     */
    val types: Map<String, SqlType> = emptyMap(),
    /**
     * Where the record's id is. By default, [MappedKey.Fields] for a resource that is `keyedBy` some fields, and a
     * `TEXT` column called `id` for the rest.
     */
    val key: MappedKey? = null,
    /** Whether writes are refused, with [ReadOnlyTableException], for a table the application must not change. */
    val readOnly: Boolean = false,
) {
    /** The key this table has for [resource]. */
    internal fun keyOf(resource: Resource): MappedKey =
        key ?: if (resource.id.source == IdSource.KEY) MappedKey.Fields else MappedKey.Column()

    /** The column of [field]. */
    internal fun columnOf(field: String): String = columns[field] ?: field

    /** The column type of a field of [resource] called [field], of type [type]. */
    internal fun typeOf(field: String, type: FieldType): SqlType = types[field] ?: SqlType.defaultFor(type)

    internal fun problems(resource: Resource): List<String> = buildList {
        val name = resource.name
        if (table.isBlank()) add("The table of '$name' has no name.")
        for (field in columns.keys + types.keys) {
            if (field == Resource.ID) add("The mapping of '$name' gives the id a column or type: set its key instead.")
            else if (resource.field(field) == null) add("The mapping of '$name' names the field '$field', which it doesn't have.")
        }
        for ((field, column) in columns) if (column.isBlank()) add("The column of '$name.$field' has no name.")
        for (field in resource.fields) {
            val type = typeOf(field.name, field.type)
            if (type !in SqlType.allowedFor(field.type)) {
                add(
                    "'$name.${field.name}' is ${field.type.article()} field, which can't be kept in ${type.phrase} column; " +
                        "it can be ${SqlType.allowedFor(field.type).joinToString(" or ")}.",
                )
            }
        }
        val key = keyOf(resource)
        when (key) {
            is MappedKey.Column -> {
                if (key.name.isBlank()) add("The key column of '$name' has no name.")
                if (key.type !in SqlType.KEYS) {
                    add("The key of '$name' can't be ${key.type.phrase} column; it can be ${SqlType.KEYS.joinToString(" or ")}.")
                }
                if (key.generated) {
                    if (key.type !in SqlType.GENERATED) {
                        add("The database can generate only an INTEGER or BIGINT key, so the key of '$name' can't be a generated ${key.type}.")
                    }
                    if (resource.id.source != IdSource.GENERATED) {
                        add("'$name' has ${if (resource.id.source == IdSource.KEY) "ids made of its fields" else "natural ids"}, so its key can't be generated by the database.")
                    }
                }
            }
            MappedKey.Fields -> if (resource.id.source != IdSource.KEY) {
                add("The key of '$name' is its key fields' columns, but '$name' isn't keyedBy any fields.")
            }
        }
        val byColumn = resource.fields.groupBy { columnOf(it.name).lowercase() }
        for ((column, fields) in byColumn.filterValues { it.size > 1 }) {
            add("The fields ${fields.joinToString(" and ") { "'$name.${it.name}'" }} share the column '$column'.")
        }
        if (key is MappedKey.Column && key.name.lowercase() in byColumn) {
            add("'$name.${byColumn.getValue(key.name.lowercase()).first().name}' can't be kept in '${key.name}', the key column.")
        }
    }
}

/** Where the id of a [MappedTable]'s records is kept. */
public sealed interface MappedKey {
    /**
     * One column, called [name], whose values, read as text, are the ids.
     *
     * With [generated], the database makes each new row's key, as a `bigserial`, an `AUTO_INCREMENT` or an identity
     * column does, so the resource's ids must be generated ones, the column [SqlType.INTEGER] or [SqlType.BIGINT], and
     * a create that gives an id is refused.
     */
    public data class Column(
        /** The column's name. */
        val name: String = Resource.ID,
        /** The column's type: [SqlType.TEXT], [SqlType.INTEGER], [SqlType.BIGINT] or [SqlType.UUID]. */
        val type: SqlType = SqlType.TEXT,
        /** Whether the database makes the key of each new row. */
        val generated: Boolean = false,
    ) : MappedKey

    /**
     * The columns of the resource's `keyedBy` fields, together, as the table's primary key of several columns. A
     * record's id is made of their values as `keyedBy` makes it, such as `alice~7`.
     */
    public data object Fields : MappedKey
}

/**
 * The type of a column in a [MappedTable]. Each field type can be kept in some of them, and BaseKt reads and writes
 * the column as the field's type:
 *
 * | Field | Column types |
 * |---|---|
 * | string, enum, file | [TEXT] |
 * | reference | [TEXT], [INTEGER], [BIGINT], [UUID]: the type of the key it points to |
 * | int | [INTEGER], [BIGINT] |
 * | long | [BIGINT] |
 * | decimal | [DECIMAL] |
 * | bool | [BOOLEAN] |
 * | instant | [TIMESTAMPTZ], or [TIMESTAMP] holding UTC times |
 *
 * A reference or id kept in a number or UUID column is text that holds a number or a UUID. A value that isn't one
 * matches no row, and a write of one is refused. Such columns sort by their own order, so numbers in numeric order.
 */
public enum class SqlType {
    /** Any text column: `TEXT`, `VARCHAR(n)`, `CHAR(n)` and the like. */
    TEXT,

    /** A 32-bit integer column, `INTEGER` or `SERIAL`, or a smaller one. */
    INTEGER,

    /** A 64-bit integer column, `BIGINT` or `BIGSERIAL`. */
    BIGINT,

    /** An exact decimal column, `DECIMAL(p, s)` or `NUMERIC`. */
    DECIMAL,

    /** A `BOOLEAN` column. */
    BOOLEAN,

    /** A `TIMESTAMP` column without a time zone, whose values are UTC times. */
    TIMESTAMP,

    /** A `TIMESTAMP WITH TIME ZONE` column, `timestamptz` on Postgres. */
    TIMESTAMPTZ,

    /** A `UUID` column. */
    UUID,
    ;

    internal companion object {
        /** The types a single key column can have. */
        val KEYS: List<SqlType> = listOf(TEXT, INTEGER, BIGINT, UUID)

        /** The types a database generates keys of. */
        val GENERATED: List<SqlType> = listOf(INTEGER, BIGINT)

        fun defaultFor(type: FieldType): SqlType = allowedFor(type).first()

        fun allowedFor(type: FieldType): List<SqlType> = when (type) {
            FieldType.STRING, FieldType.ENUM, FieldType.FILE -> listOf(TEXT)
            FieldType.REFERENCE -> KEYS
            FieldType.INT -> listOf(INTEGER, BIGINT)
            FieldType.LONG -> listOf(BIGINT)
            FieldType.DECIMAL -> listOf(DECIMAL)
            FieldType.BOOL -> listOf(BOOLEAN)
            FieldType.INSTANT -> listOf(TIMESTAMPTZ, TIMESTAMP)
        }
    }
}

/** The type's name with its article, as in "an INTEGER". */
internal val SqlType.phrase: String get() = (if (name.first() in "AEIOU") "an " else "a ") + name

private fun FieldType.article(): String {
    val word = name.lowercase()
    return if (word.first() in "aeiou") "an $word" else "a $word"
}
