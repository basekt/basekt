package io.github.basekt.server

import io.github.basekt.data.AggregateFunction
import io.github.basekt.data.AggregateQuery
import io.github.basekt.data.Comparison
import io.github.basekt.data.Direction
import io.github.basekt.data.Filter
import io.github.basekt.data.Measure
import io.github.basekt.data.Query
import io.github.basekt.data.Sort
import io.github.basekt.data.fieldType
import io.github.basekt.schema.Field
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
import io.github.basekt.schema.parse

/**
 * Turns URL query parameters into a [Query] for [resource]:
 * - `field=value` keeps records whose field equals the value;
 * - `field[op]=value`, with `op` one of `eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `in` (comma-separated values),
 *   `contains` or `null` (`true` or `false`);
 * - `sort=-price,name` sorts by each field in turn, descending when prefixed with `-`;
 * - `limit` sets the page size, between 1 and [maxLimit] ([defaultLimit] when absent);
 * - `cursor` is the previous page's `nextCursor`.
 *
 * Every filter must match. Throws [ApiException] with [ErrorCode.INVALID_QUERY] for anything it can't parse.
 */
public fun parseQuery(
    resource: Resource,
    parameters: Map<String, List<String>>,
    defaultLimit: Int,
    maxLimit: Int,
): Query {
    val filters = mutableListOf<Filter>()
    var sort = emptyList<Sort>()
    var limit = defaultLimit
    var cursor: String? = null
    for ((name, values) in parameters) {
        val value = values.lastOrNull() ?: continue
        when (name) {
            "sort" -> sort = parseSort(resource, value)
            "limit" -> limit = value.toIntOrNull()?.takeIf { it in 1..maxLimit }
                ?: invalidQuery("limit must be a whole number from 1 to $maxLimit.")
            "cursor" -> cursor = value
            else -> filters += parseFilter(resource, name, value)
        }
    }
    val filter = when (filters.size) {
        0 -> null
        1 -> filters.single()
        else -> Filter.And(filters)
    }
    return Query(filter, sort, limit, cursor)
}

/**
 * Turns URL query parameters into an [AggregateQuery] for [resource]:
 * - `groupBy=kind,siteId` groups by those fields;
 * - `count` or `count=*` counts records, and `count=field` the records with the field set: `count=*,field` asks
 *   for both;
 * - `sum=`, `avg=`, `min=` and `max=` take one or more fields, comma-separated, each a measure named
 *   `sum_<field>` and so on;
 * - `sort=-sum_quantity,kind` sorts by measures and group fields, descending when prefixed with `-`;
 * - `limit` caps the groups, between 1 and [maxLimit] ([maxLimit] when absent);
 * - any other parameter is a filter, as in [parseQuery].
 *
 * Throws [ApiException] with [ErrorCode.INVALID_QUERY] for anything it can't parse.
 */
public fun parseAggregateQuery(resource: Resource, parameters: Map<String, List<String>>, maxLimit: Int): AggregateQuery {
    val filters = mutableListOf<Filter>()
    val measures = mutableListOf<Measure>()
    var groupBy = emptyList<String>()
    var sort = emptyList<Sort>()
    var limit = maxLimit
    for ((name, values) in parameters) {
        // A bare `?count` has no value at all.
        val value = values.lastOrNull() ?: ""
        val function = AggregateFunction.entries.firstOrNull { it.name.lowercase() == name }
        when {
            name == "groupBy" -> groupBy = value.split(',').filter { it.isNotBlank() }
            name == "sort" -> sort = value.split(',').filter { it.isNotBlank() }.map { key ->
                Sort(key.removePrefix("-"), if (key.startsWith('-')) Direction.DESC else Direction.ASC)
            }
            name == "limit" -> limit = value.toIntOrNull()?.takeIf { it in 1..maxLimit }
                ?: invalidQuery("limit must be a whole number from 1 to $maxLimit.")
            function != null -> for (given in values.ifEmpty { listOf("") }) {
                val fields = given.split(',').filter { it.isNotBlank() }
                when {
                    fields.isNotEmpty() -> measures += fields.map { field ->
                        if (field == ALL_RECORDS && function == AggregateFunction.COUNT) Measure(function) else Measure(function, field)
                    }
                    function == AggregateFunction.COUNT -> measures += Measure(function)
                    else -> invalidQuery("$name needs at least one field.")
                }
            }
            else -> filters += parseFilter(resource, name, value)
        }
    }
    if (measures.isEmpty()) invalidQuery("An aggregate needs at least one of count, sum, avg, min or max.")
    val filter = when (filters.size) {
        0 -> null
        1 -> filters.single()
        else -> Filter.And(filters)
    }
    return AggregateQuery(groupBy, measures, filter, sort, limit)
}

/** The `count` of every record, as opposed to of a field. */
private const val ALL_RECORDS = "*"

private val filterName = Regex("""([A-Za-z][A-Za-z0-9_]*)(?:\[([a-z]+)])?""")

private fun parseFilter(resource: Resource, name: String, text: String): Filter {
    val match = filterName.matchEntire(name) ?: invalidQuery("'$name' is not a field or a query parameter.")
    val fieldName = match.groupValues[1]
    val op = match.groupValues[2].ifEmpty { "eq" }
    val field = field(resource, fieldName)
    fun value(raw: String): Value =
        field.parse(raw) ?: invalidQuery("'$raw' is not a valid value for '$fieldName'.")
    return when (op) {
        "eq" -> Filter.Compare(fieldName, Comparison.EQ, value(text))
        "ne" -> Filter.Compare(fieldName, Comparison.NE, value(text))
        "gt" -> Filter.Compare(fieldName, Comparison.GT, value(text))
        "gte" -> Filter.Compare(fieldName, Comparison.GTE, value(text))
        "lt" -> Filter.Compare(fieldName, Comparison.LT, value(text))
        "lte" -> Filter.Compare(fieldName, Comparison.LTE, value(text))
        "in" -> Filter.In(fieldName, text.split(',').map(::value))
        "contains" -> Filter.Contains(fieldName, text)
        "null" -> Filter.IsNull(fieldName, text.toBooleanStrictOrNull() ?: invalidQuery("[null] takes true or false."))
        else -> invalidQuery("'$op' is not a filter operator.")
    }
}

private fun parseSort(resource: Resource, text: String): List<Sort> = text.split(',').filter { it.isNotBlank() }.map { key ->
    val descending = key.startsWith('-')
    val fieldName = key.removePrefix("-")
    field(resource, fieldName)
    Sort(fieldName, if (descending) Direction.DESC else Direction.ASC)
}

/** Returns [name]'s field, treating [Resource.ID] as a string field. */
private fun field(resource: Resource, name: String): Field =
    resource.field(name) ?: if (name == Resource.ID) Field(Resource.ID, resource.fieldType(name))
    else invalidQuery("'${resource.name}' has no field '$name'.")

private fun invalidQuery(message: String): Nothing = throw ApiException(ErrorCode.INVALID_QUERY, message)
