package io.github.basekt.server

import io.github.basekt.data.Comparison
import io.github.basekt.data.Filter
import io.github.basekt.data.Page
import io.github.basekt.data.Query
import io.github.basekt.schema.FieldType
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The application's own code that serves the records of a [Resource.provided] resource, such as what a queue holds
 * right now or what another service owns behind its API. Register it with [ProvidersBuilder.provide].
 *
 * The service checks every call before it reaches the provider: the caller's permission, and a query that only uses
 * the [filters] and [sorts] it declares. The caller's read scope arrives as part of [Query.filter], as a
 * [Filter.In] on each scoped field, so a provider of a scoped resource declares [FilterOperator.IN] on those fields.
 * The service checks the scope again on what comes back.
 *
 * What it returns are plain records of the resource's fields, as from a [io.github.basekt.data.DataSource]: a text
 * [Resource.ID], values of each field's type, and no other keys. A field left out is `null`. Anything else it
 * returns, and anything it throws other than [ApiException] or [io.github.basekt.data.InvalidQueryException], is a
 * failure: the caller gets [ErrorCode.UNAVAILABLE] with the request id, and the server logs the cause.
 */
public interface RecordProvider {
    /**
     * The filters the provider applies, as the operators it supports on each field, which may include [Resource.ID].
     * A list with any other is refused with [ErrorCode.INVALID_QUERY] before the provider is called. None by default.
     */
    public val filters: Map<String, Set<FilterOperator>> get() = emptyMap()

    /** The fields, [Resource.ID] included, that the provider can sort by. None by default: records come in id order. */
    public val sorts: Set<String> get() = emptySet()

    /**
     * Returns one page of the records that match [query]: at most [Query.limit] of them, in its order and then by id,
     * starting after [Query.cursor], which is a [Page.nextCursor] this provider issued. The filter is made of
     * [Filter.Compare], [Filter.In], [Filter.Contains] and [Filter.IsNull] on the declared fields, joined by
     * [Filter.And] and [Filter.Or]; an [Filter.Or] comes from a search over several fields or from a scope with
     * several ways in.
     */
    public suspend fun list(query: Query): Page

    /** Returns the record with [id], or `null` if there isn't one. */
    public suspend fun get(id: String): Record?
}

/** An operator a list filter uses, named as in a `field[op]=value` query parameter. See [parseQuery]. */
@Serializable
public enum class FilterOperator {
    /** Equal: `field=value` or `field[eq]=value`. */
    @SerialName("eq") EQ,

    /** Not equal. */
    @SerialName("ne") NE,

    /** Greater than. */
    @SerialName("gt") GT,

    /** Greater than or equal. */
    @SerialName("gte") GTE,

    /** Less than. */
    @SerialName("lt") LT,

    /** Less than or equal. */
    @SerialName("lte") LTE,

    /** One of several values. */
    @SerialName("in") IN,

    /** Contains a text, ignoring case: string, enum and reference fields only. */
    @SerialName("contains") CONTAINS,

    /** Is null, or is set. */
    @SerialName("null") NULL,
}

/** Registers the providers of a schema's [Resource.provided] resources. */
public class ProvidersBuilder {
    private val providers = linkedMapOf<String, RecordProvider>()

    /** Serves the records of the provided resource [resource] with [provider]. */
    public fun provide(resource: String, provider: RecordProvider) {
        require(resource !in providers) { "The resource '$resource' has a provider registered twice." }
        providers[resource] = provider
    }

    /** Returns the providers registered so far, by resource name. */
    public fun build(): Map<String, RecordProvider> = providers.toMap()
}

/**
 * Returns every problem with [providers] for this schema: a provided resource without one, one for a resource that
 * isn't provided, and filters or sorts on fields the API doesn't serve. A scoped field needs [FilterOperator.IN].
 */
internal fun Schema.providerProblems(providers: Map<String, RecordProvider>): List<String> {
    val problems = mutableListOf<String>()
    for (name in providers.keys) {
        val resource = resource(name)
        when {
            resource == null -> problems += "A provider is registered for '$name', which the schema doesn't have."
            !resource.provided -> problems += "A provider is registered for '$name', which isn't provided(): its records live in the data source."
        }
    }
    for (resource in resources.filter { it.provided }) {
        val provider = providers[resource.name]
        if (provider == null) {
            problems += "'${resource.name}' is provided(), but no provider is registered for it."
            continue
        }
        for ((field, operators) in provider.filters) {
            val type = resource.servedType(field)
            when {
                type == null -> problems += "The provider of '${resource.name}' filters on '$field', which isn't a field the API serves."
                FilterOperator.CONTAINS in operators && type !in textTypes ->
                    problems += "The provider of '${resource.name}' searches '$field', which isn't a text field."
            }
        }
        for (field in provider.sorts) {
            if (resource.servedType(field) == null) problems += "The provider of '${resource.name}' sorts by '$field', which isn't a field the API serves."
        }
        val scoped = resource.permissions.scopes.flatMap { scope -> scope.clauses.map { it.field } }.distinct()
        for (field in scoped) {
            if (FilterOperator.IN !in provider.filters[field].orEmpty()) {
                problems += "'${resource.name}' is scoped by '$field', so its provider must support the 'in' filter on it."
            }
        }
    }
    return problems
}

/** Returns the type of [field] if the API serves it, treating [Resource.ID] as a string, or `null`. */
private fun Resource.servedType(field: String): FieldType? =
    if (field == Resource.ID) FieldType.STRING else field(field)?.takeUnless { it.hidden }?.type

private val textTypes = setOf(FieldType.STRING, FieldType.ENUM, FieldType.REFERENCE)

/** Refuses a [query] on [resource] that uses a filter or a sort that [provider] doesn't declare. */
internal fun checkProvidedQuery(resource: Resource, provider: RecordProvider, query: Query) {
    query.filter?.let { checkProvidedFilter(resource, provider, it) }
    for (sort in query.sort) {
        if (sort.field !in provider.sorts) {
            val offered = provider.sorts.sorted().ifEmpty { listOf("nothing") }.joinToString()
            throw ApiException(ErrorCode.INVALID_QUERY, "'${resource.name}' can't be sorted by '${sort.field}'. It sorts by $offered.")
        }
    }
}

private fun checkProvidedFilter(resource: Resource, provider: RecordProvider, filter: Filter) {
    val (field, operator) = when (filter) {
        is Filter.And -> return filter.filters.forEach { checkProvidedFilter(resource, provider, it) }
        is Filter.Or -> return filter.filters.forEach { checkProvidedFilter(resource, provider, it) }
        is Filter.Not -> throw ApiException(ErrorCode.INVALID_QUERY, "'${resource.name}' can't be filtered with a negation.")
        is Filter.Compare -> filter.field to filter.op.operator()
        is Filter.In -> filter.field to FilterOperator.IN
        is Filter.Contains -> filter.field to FilterOperator.CONTAINS
        is Filter.IsNull -> filter.field to FilterOperator.NULL
    }
    val supported = provider.filters[field].orEmpty()
    if (operator !in supported) {
        val offered = supported.sorted().joinToString { it.name.lowercase() }
        throw ApiException(
            ErrorCode.INVALID_QUERY,
            if (offered.isEmpty()) "'${resource.name}' can't be filtered on '$field'."
            else "'${resource.name}' can't filter '$field' with ${operator.name.lowercase()}. It takes $offered.",
        )
    }
}

private fun Comparison.operator(): FilterOperator = when (this) {
    Comparison.EQ -> FilterOperator.EQ
    Comparison.NE -> FilterOperator.NE
    Comparison.GT -> FilterOperator.GT
    Comparison.GTE -> FilterOperator.GTE
    Comparison.LT -> FilterOperator.LT
    Comparison.LTE -> FilterOperator.LTE
}

/**
 * Returns [record] as [resource]'s record with every field, `null` for one it leaves out, or throws
 * [IllegalStateException] naming what is wrong with it.
 */
internal fun Resource.providedRecord(record: Record): Record {
    check(record[Resource.ID] is Value.Text) { "The provider of '$name' returned a record without a text id." }
    val unknown = record.keys - Resource.ID - fields.map { it.name }.toSet()
    check(unknown.isEmpty()) { "The provider of '$name' returned fields the resource doesn't have: ${unknown.sorted().joinToString()}." }
    for (field in fields) {
        val value = record[field.name] ?: continue
        check(value.fits(field.type)) { "The provider of '$name' returned '${field.name}' as '$value', which isn't a ${field.type.name.lowercase()}." }
    }
    return fields.associate { it.name to record[it.name] } + (Resource.ID to record[Resource.ID])
}

/** Whether this value has [type]'s kind. */
internal fun Value.fits(type: FieldType): Boolean = when (type) {
    FieldType.STRING, FieldType.ENUM, FieldType.REFERENCE, FieldType.FILE -> this is Value.Text
    FieldType.INT, FieldType.LONG -> this is Value.Integer
    FieldType.DECIMAL -> this is Value.Decimal
    FieldType.BOOL -> this is Value.Bool
    FieldType.INSTANT -> this is Value.Timestamp
}

/** Whether [filter] matches no record at all: an empty [Filter.Or], as a scope makes for a caller with no values. */
internal fun matchesNothing(filter: Filter?): Boolean = when (filter) {
    is Filter.Or -> filter.filters.isEmpty() || filter.filters.all { matchesNothing(it) }
    is Filter.And -> filter.filters.any { matchesNothing(it) }
    else -> false
}
