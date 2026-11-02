package io.github.basekt.data

import io.github.basekt.schema.FieldType
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
import io.github.basekt.schema.isStructured

/**
 * What [DataSource.list] returns: records that match [filter], ordered by [sort], at most [limit] of them, starting
 * after [cursor].
 *
 * Semantics every adapter follows:
 * - A comparison with a field that is null or unset is false, as in SQL. [Filter.IsNull] finds those records.
 * - Sorting always ends with [Resource.ID] ascending, so the order is total. Without [sort], records come in id order.
 * - Nulls sort after values in ascending order and before them in descending order.
 * - A cursor is opaque. It is only valid for the same resource and [sort] it was issued with.
 */
public data class Query(
    /** Which records to return, or `null` for all of them. */
    val filter: Filter? = null,
    /** The order, most significant first. */
    val sort: List<Sort> = emptyList(),
    /** The page size, at least 1. */
    val limit: Int = 50,
    /** The [Page.nextCursor] of the previous page, or `null` for the first page. */
    val cursor: String? = null,
) {
    init {
        require(limit >= 1) { "A query's limit must be at least 1, not $limit." }
    }
}

/** One page of records, and the cursor of the next page. */
public data class Page(
    /** The records, in the query's order. */
    val items: List<Record>,
    /** The cursor of the next page, or `null` if this is the last one. */
    val nextCursor: String?,
) {
    /** Whether there is a next page. */
    val hasNext: Boolean get() = nextCursor != null
}

/** One sort key. */
public data class Sort(
    /** The field to sort by, which may be [Resource.ID]. */
    val field: String,
    /** The direction. */
    val direction: Direction = Direction.ASC,
)

/** A sort direction. */
public enum class Direction {
    /** Smallest first. */
    ASC,

    /** Largest first. */
    DESC,
}

/** A comparison operator of [Filter.Compare]. */
public enum class Comparison {
    /** Equal. */
    EQ,

    /** Not equal. */
    NE,

    /** Greater than. */
    GT,

    /** Greater than or equal. */
    GTE,

    /** Less than. */
    LT,

    /** Less than or equal. */
    LTE,
}

/** A condition on a record's fields. Field names may include [Resource.ID]. */
public sealed interface Filter {
    /** The field compares to [value] with [op]. */
    public data class Compare(
        /** The field. */
        val field: String,
        /** The operator. */
        val op: Comparison,
        /** The value, of the field's type. */
        val value: Value,
    ) : Filter

    /** The field equals one of [values]. */
    public data class In(
        /** The field. */
        val field: String,
        /** The accepted values, of the field's type. */
        val values: List<Value>,
    ) : Filter

    /** The text field contains [text], ignoring case. */
    public data class Contains(
        /** The field, a string, enum or reference field. */
        val field: String,
        /** The text to look for. */
        val text: String,
    ) : Filter

    /** The list field holds at least one of [values]. Only a list field of scalars can be asked this. */
    public data class Has(
        /** The list field. */
        val field: String,
        /** The values to look for, of the list's item type. */
        val values: List<Value>,
    ) : Filter

    /** The field is null or unset when [isNull] is true, and set otherwise. */
    public data class IsNull(
        /** The field. */
        val field: String,
        /** Whether to match null or non-null values. */
        val isNull: Boolean = true,
    ) : Filter

    /** Every one of [filters] matches. An empty list matches everything. */
    public data class And(
        /** The conditions. */
        val filters: List<Filter>,
    ) : Filter

    /** At least one of [filters] matches. An empty list matches nothing. */
    public data class Or(
        /** The conditions. */
        val filters: List<Filter>,
    ) : Filter

    /** [filter] doesn't match, including when it compared a null field. */
    public data class Not(
        /** The negated condition. */
        val filter: Filter,
    ) : Filter
}

/**
 * Checks that [query] fits this resource: every field exists, and every value has the field's type. Throws
 * [InvalidQueryException] otherwise. Adapters call this before running a query.
 */
public fun Resource.checkQuery(query: Query) {
    query.filter?.let { checkFilter(it) }
    for (sort in query.sort) {
        fieldType(sort.field)
        if (field(sort.field)?.isStructured == true) throw InvalidQueryException("'${sort.field}' is a list, so it can't be sorted on.")
    }
}

/** Returns the type of [field] on this resource, treating [Resource.ID] as a string, or throws [InvalidQueryException]. */
public fun Resource.fieldType(field: String): FieldType =
    if (field == Resource.ID) FieldType.STRING
    else field(field)?.type ?: throw InvalidQueryException("'$name' has no field '$field'.")

private fun Resource.checkFilter(filter: Filter) {
    fun scalar(name: String) {
        if (field(name)?.isStructured == true) {
            throw InvalidQueryException("'$name' is a list: ask whether it has a value with the has filter.")
        }
    }
    when (filter) {
        is Filter.Compare -> {
            scalar(filter.field)
            checkValue(filter.field, filter.value)
        }
        is Filter.In -> {
            scalar(filter.field)
            filter.values.forEach { checkValue(filter.field, it) }
        }
        is Filter.Contains -> {
            scalar(filter.field)
            if (!fieldType(filter.field).isText()) {
                throw InvalidQueryException("'${filter.field}' is not a text field, so it can't be searched.")
            }
        }
        is Filter.Has -> {
            val field = field(filter.field)
            if (field == null || !field.list || field.type == FieldType.OBJECT) {
                throw InvalidQueryException("'${filter.field}' is not a list of values, so has doesn't apply to it.")
            }
            filter.values.forEach { checkValue(filter.field, it) }
        }
        is Filter.IsNull -> fieldType(filter.field)
        is Filter.And -> filter.filters.forEach { checkFilter(it) }
        is Filter.Or -> filter.filters.forEach { checkFilter(it) }
        is Filter.Not -> checkFilter(filter.filter)
    }
}

private fun Resource.checkValue(field: String, value: Value) {
    val type = fieldType(field)
    val fits = when (type) {
        FieldType.STRING, FieldType.ENUM, FieldType.REFERENCE, FieldType.FILE -> value is Value.Text
        FieldType.INT, FieldType.LONG -> value is Value.Integer
        FieldType.DECIMAL -> value is Value.Decimal
        FieldType.BOOL -> value is Value.Bool
        FieldType.INSTANT -> value is Value.Timestamp
        FieldType.OBJECT -> false
    }
    if (!fits) throw InvalidQueryException("'$field' can't be compared with '$value'.")
}

private fun FieldType.isText(): Boolean =
    this == FieldType.STRING || this == FieldType.ENUM || this == FieldType.REFERENCE
