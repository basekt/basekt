package io.github.basekt.data

import io.github.basekt.schema.Model
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
import kotlin.reflect.KProperty1
import kotlin.time.Instant

/**
 * Builds a [Query] on a [Model] of [T] from property references:
 *
 * ```kotlin
 * where { (Item::price lt Value.Decimal.of("10")) and (Item::visible eq true) }
 * orderBy(Item::price, Direction.DESC)
 * ```
 *
 * Comparisons only compile when the value has the property's type, and they translate to the same [Filter]s a
 * dynamic query uses, so every adapter runs them the same way.
 */
public class TypedQuery<T> internal constructor(private val model: Model<T>) {
    private var filter: Filter? = null
    private val sort = mutableListOf<Sort>()

    /** The page size, at least 1. */
    public var limit: Int = 50

    /** The previous page's [Page.nextCursor], or `null` for the first page. */
    public var cursor: String? = null

    /** Keeps the records that match [block]'s filter. Calling it again adds another filter that must also match. */
    public fun where(block: FilterScope<T>.() -> Filter) {
        val added = FilterScope(model).block()
        filter = filter?.let { Filter.And(listOf(it, added)) } ?: added
    }

    /** Sorts by [property] in [direction], after the sort keys added before. */
    public fun orderBy(property: KProperty1<T, *>, direction: Direction = Direction.ASC) {
        sort += Sort(model.fieldName(property), direction)
    }

    internal fun build(): Query = Query(filter, sort.toList(), limit, cursor)
}

/** The comparisons a [TypedQuery] filter is made of. Each one only accepts a value of the property's type. */
public class FilterScope<T> internal constructor(private val model: Model<T>) {
    private fun KProperty1<T, *>.compare(op: Comparison, value: Value) = Filter.Compare(model.fieldName(this), op, value)

    /** Both filters match. */
    public infix fun Filter.and(other: Filter): Filter = Filter.And(listOf(this, other))

    /** At least one of the filters matches. */
    public infix fun Filter.or(other: Filter): Filter = Filter.Or(listOf(this, other))

    /** [filter] doesn't match. */
    public fun not(filter: Filter): Filter = Filter.Not(filter)

    /** The property is null. */
    public fun KProperty1<T, *>.isNull(): Filter = Filter.IsNull(model.fieldName(this))

    /** The property isn't null. */
    public fun KProperty1<T, *>.isNotNull(): Filter = Filter.IsNull(model.fieldName(this), isNull = false)

    /** The text contains [text], ignoring case. */
    public infix fun KProperty1<T, String?>.contains(text: String): Filter = Filter.Contains(model.fieldName(this), text)

    /** Equal. */
    public infix fun KProperty1<T, String?>.eq(value: String): Filter = compare(Comparison.EQ, Value.Text(value))

    /** Not equal. */
    public infix fun KProperty1<T, String?>.ne(value: String): Filter = compare(Comparison.NE, Value.Text(value))

    /** Less than. */
    public infix fun KProperty1<T, String?>.lt(value: String): Filter = compare(Comparison.LT, Value.Text(value))

    /** Less than or equal. */
    public infix fun KProperty1<T, String?>.lte(value: String): Filter = compare(Comparison.LTE, Value.Text(value))

    /** Greater than. */
    public infix fun KProperty1<T, String?>.gt(value: String): Filter = compare(Comparison.GT, Value.Text(value))

    /** Greater than or equal. */
    public infix fun KProperty1<T, String?>.gte(value: String): Filter = compare(Comparison.GTE, Value.Text(value))

    /** One of [values]. */
    @JvmName("isInText")
    public infix fun KProperty1<T, String?>.isIn(values: Collection<String>): Filter =
        Filter.In(model.fieldName(this), values.map(Value::Text))

    /** Equal. */
    public infix fun KProperty1<T, Int?>.eq(value: Int): Filter = compare(Comparison.EQ, Value.Integer(value.toLong()))

    /** Not equal. */
    public infix fun KProperty1<T, Int?>.ne(value: Int): Filter = compare(Comparison.NE, Value.Integer(value.toLong()))

    /** Less than. */
    public infix fun KProperty1<T, Int?>.lt(value: Int): Filter = compare(Comparison.LT, Value.Integer(value.toLong()))

    /** Less than or equal. */
    public infix fun KProperty1<T, Int?>.lte(value: Int): Filter = compare(Comparison.LTE, Value.Integer(value.toLong()))

    /** Greater than. */
    public infix fun KProperty1<T, Int?>.gt(value: Int): Filter = compare(Comparison.GT, Value.Integer(value.toLong()))

    /** Greater than or equal. */
    public infix fun KProperty1<T, Int?>.gte(value: Int): Filter = compare(Comparison.GTE, Value.Integer(value.toLong()))

    /** One of [values]. */
    @JvmName("isInInt")
    public infix fun KProperty1<T, Int?>.isIn(values: Collection<Int>): Filter =
        Filter.In(model.fieldName(this), values.map { Value.Integer(it.toLong()) })

    /** Equal. */
    public infix fun KProperty1<T, Long?>.eq(value: Long): Filter = compare(Comparison.EQ, Value.Integer(value))

    /** Not equal. */
    public infix fun KProperty1<T, Long?>.ne(value: Long): Filter = compare(Comparison.NE, Value.Integer(value))

    /** Less than. */
    public infix fun KProperty1<T, Long?>.lt(value: Long): Filter = compare(Comparison.LT, Value.Integer(value))

    /** Less than or equal. */
    public infix fun KProperty1<T, Long?>.lte(value: Long): Filter = compare(Comparison.LTE, Value.Integer(value))

    /** Greater than. */
    public infix fun KProperty1<T, Long?>.gt(value: Long): Filter = compare(Comparison.GT, Value.Integer(value))

    /** Greater than or equal. */
    public infix fun KProperty1<T, Long?>.gte(value: Long): Filter = compare(Comparison.GTE, Value.Integer(value))

    /** One of [values]. */
    @JvmName("isInLong")
    public infix fun KProperty1<T, Long?>.isIn(values: Collection<Long>): Filter =
        Filter.In(model.fieldName(this), values.map(Value::Integer))

    /** Equal. */
    public infix fun KProperty1<T, Value.Decimal?>.eq(value: Value.Decimal): Filter = compare(Comparison.EQ, value)

    /** Not equal. */
    public infix fun KProperty1<T, Value.Decimal?>.ne(value: Value.Decimal): Filter = compare(Comparison.NE, value)

    /** Less than. */
    public infix fun KProperty1<T, Value.Decimal?>.lt(value: Value.Decimal): Filter = compare(Comparison.LT, value)

    /** Less than or equal. */
    public infix fun KProperty1<T, Value.Decimal?>.lte(value: Value.Decimal): Filter = compare(Comparison.LTE, value)

    /** Greater than. */
    public infix fun KProperty1<T, Value.Decimal?>.gt(value: Value.Decimal): Filter = compare(Comparison.GT, value)

    /** Greater than or equal. */
    public infix fun KProperty1<T, Value.Decimal?>.gte(value: Value.Decimal): Filter = compare(Comparison.GTE, value)

    /** Equal. */
    public infix fun KProperty1<T, Instant?>.eq(value: Instant): Filter = compare(Comparison.EQ, Value.Timestamp(value))

    /** Not equal. */
    public infix fun KProperty1<T, Instant?>.ne(value: Instant): Filter = compare(Comparison.NE, Value.Timestamp(value))

    /** Before. */
    public infix fun KProperty1<T, Instant?>.lt(value: Instant): Filter = compare(Comparison.LT, Value.Timestamp(value))

    /** At or before. */
    public infix fun KProperty1<T, Instant?>.lte(value: Instant): Filter = compare(Comparison.LTE, Value.Timestamp(value))

    /** After. */
    public infix fun KProperty1<T, Instant?>.gt(value: Instant): Filter = compare(Comparison.GT, Value.Timestamp(value))

    /** At or after. */
    public infix fun KProperty1<T, Instant?>.gte(value: Instant): Filter = compare(Comparison.GTE, Value.Timestamp(value))

    /** Equal. */
    public infix fun KProperty1<T, Boolean?>.eq(value: Boolean): Filter = compare(Comparison.EQ, Value.Bool(value))

    /** Not equal. */
    public infix fun KProperty1<T, Boolean?>.ne(value: Boolean): Filter = compare(Comparison.NE, Value.Bool(value))

    /** Equal. */
    public infix fun <E : Enum<E>> KProperty1<T, E?>.eq(value: E): Filter = compare(Comparison.EQ, model.enumValue(this, value))

    /** Not equal. */
    public infix fun <E : Enum<E>> KProperty1<T, E?>.ne(value: E): Filter = compare(Comparison.NE, model.enumValue(this, value))

    /** One of [values]. */
    @JvmName("isInEnum")
    public infix fun <E : Enum<E>> KProperty1<T, E?>.isIn(values: Collection<E>): Filter =
        Filter.In(model.fieldName(this), values.map { model.enumValue(this, it) })
}

/** Returns [property]'s field name, or throws [InvalidQueryException] if the model has no such field. */
internal fun Model<*>.fieldName(property: KProperty1<*, *>): String {
    val name = property.name
    if (name != Resource.ID && resource.field(name) == null) {
        throw InvalidQueryException("'${resource.name}' has no field '$name'. Properties renamed with @SerialName can't be queried by reference.")
    }
    return name
}

/** Returns [property]'s field name for a write, refusing [Resource.ID] and unknown fields. */
internal fun Model<*>.writableFieldName(property: KProperty1<*, *>): String {
    val name = property.name
    if (name == Resource.ID) throw InvalidQueryException("'${resource.name}.id' is the record's id and can't be changed.")
    if (resource.field(name) == null) {
        throw InvalidQueryException("'${resource.name}' has no field '$name'. Properties renamed with @SerialName can't be written by reference.")
    }
    return name
}

/** Returns the stored text of [value]: its serial name in [property]'s enum. */
internal fun Model<*>.enumValue(property: KProperty1<*, *>, value: Enum<*>): Value.Text {
    val descriptor = serializer.descriptor
    val index = (0 until descriptor.elementsCount).first { descriptor.getElementName(it) == fieldName(property) }
    return Value.Text(descriptor.getElementDescriptor(index).getElementName(value.ordinal))
}
