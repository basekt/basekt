package io.github.basekt.data

import io.github.basekt.schema.FieldType
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value

/**
 * An optional [DataSource] capability: an update that checks a condition and adds to number fields in one atomic
 * step, so concurrent writers can't both act on a value only one of them should see. "Take one if any are left" is
 * `updateIf(resource, id, Filter.Compare("left", Comparison.GTE, Value.Integer(1)), increments = mapOf("left" to
 * Value.Integer(-1)))`. Callers check for it with `source as? ConditionalWrites`. A source that offers it offers it on
 * the [DataSource] its [DataSource.transaction] passes to the block too.
 *
 * The contract:
 * - The condition is checked against the stored record as the write happens. Concurrent calls that each match the
 *   record before the others write never all succeed when the writes would make it stop matching.
 * - [Filter] semantics are [Query]'s: a comparison with a null field is false.
 * - An increment adds to an int, long or decimal field; a null field stays null.
 * - Inside a transaction, the write commits or rolls back with it.
 */
public interface ConditionalWrites {
    /**
     * Applies [changes] and adds [increments] to the record of [resource] with [id], if it matches [condition] (any
     * record when `null`). Throws [InvalidQueryException] if the condition doesn't fit the resource, an increment
     * isn't a number of its field's type, or a field is both changed and incremented.
     */
    public suspend fun updateIf(
        resource: Resource,
        id: String,
        condition: Filter?,
        changes: Record = emptyMap(),
        increments: Map<String, Value> = emptyMap(),
    ): ConditionalUpdate
}

/** What [ConditionalWrites.updateIf] did. */
public sealed interface ConditionalUpdate {
    /** The record matched and was written. */
    public data class Updated(
        /** The record after the write. */
        val record: Record,
    ) : ConditionalUpdate

    /** The record exists but didn't match the condition, so nothing changed. */
    public data object ConditionFailed : ConditionalUpdate

    /** There is no record with that id. */
    public data object NotFound : ConditionalUpdate
}

/**
 * Checks the arguments of [ConditionalWrites.updateIf] against this resource and throws [InvalidQueryException] if
 * they don't fit. Adapters call it first.
 */
public fun Resource.checkConditionalUpdate(condition: Filter?, changes: Record, increments: Map<String, Value>) {
    checkQuery(Query(condition))
    for ((name, amount) in increments) {
        val type = fieldType(name)
        val fits = when (type) {
            FieldType.INT, FieldType.LONG -> amount is Value.Integer
            FieldType.DECIMAL -> amount is Value.Decimal
            else -> false
        }
        if (!fits) throw InvalidQueryException("'$name' can't be incremented by '$amount'.")
        if (name in changes) throw InvalidQueryException("'$name' can't be both changed and incremented.")
    }
}

/** Returns this number plus [amount], a value of the same kind. */
public operator fun Value.plus(amount: Value): Value = when {
    this is Value.Integer && amount is Value.Integer -> Value.Integer(Math.addExact(value, amount.value))
    this is Value.Decimal && amount is Value.Decimal ->
        Value.Decimal.of(java.math.BigDecimal(literal).add(java.math.BigDecimal(amount.literal)).stripTrailingZeros().toPlainString())
    else -> throw InvalidQueryException("'$this' and '$amount' can't be added.")
}
