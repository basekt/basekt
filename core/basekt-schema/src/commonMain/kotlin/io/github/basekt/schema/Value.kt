package io.github.basekt.schema

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * A record: field names, including [Resource.ID], to values. A `null` value is an explicit null; an absent key is a
 * field that isn't set.
 */
public typealias Record = Map<String, Value?>

/**
 * A typed field value. Values of the same kind are ordered; comparing values of different kinds throws
 * [IllegalArgumentException].
 */
public sealed interface Value : Comparable<Value> {
    /** Text: the value of string, enum and reference fields and of [Resource.ID]. */
    public data class Text(
        /** The text. */
        val value: String,
    ) : Value {
        override fun compareTo(other: Value): Int = value.compareTo(other.expect<Text>(this).value)
        override fun toString(): String = value
    }

    /** A whole number: the value of int and long fields. */
    public data class Integer(
        /** The number. */
        val value: Long,
    ) : Value {
        override fun compareTo(other: Value): Int = value.compareTo(other.expect<Integer>(this).value)
        override fun toString(): String = value.toString()
    }

    /**
     * An exact decimal number, kept as its canonical literal so no precision is lost. Create one with [Decimal.of].
     * It serializes as its literal, so [Model] classes can have decimal properties.
     */
    @Serializable(with = DecimalSerializer::class)
    public class Decimal private constructor(
        /** The canonical literal: no leading or trailing zeros, and no `-0`. */
        public val literal: String,
    ) : Value {
        override fun compareTo(other: Value): Int = compareDecimals(literal, other.expect<Decimal>(this).literal)
        override fun equals(other: Any?): Boolean = other is Decimal && other.literal == literal
        override fun hashCode(): Int = literal.hashCode()
        override fun toString(): String = literal

        /** Parses decimals. */
        public companion object {
            private val syntax = Regex("""-?\d+(\.\d+)?""")

            /** Parses [literal], such as `"12.50"` or `"-3"`, or returns `null` if it isn't a plain decimal. */
            public fun parseOrNull(literal: String): Decimal? =
                if (syntax.matches(literal)) Decimal(canonical(literal)) else null

            /** Parses [literal], or throws [IllegalArgumentException] if it isn't a plain decimal. */
            public fun of(literal: String): Decimal =
                requireNotNull(parseOrNull(literal)) { "'$literal' is not a decimal number" }

            private fun canonical(literal: String): String {
                val negative = literal.startsWith('-')
                val digits = literal.removePrefix("-")
                val whole = digits.substringBefore('.').trimStart('0').ifEmpty { "0" }
                val fraction = digits.substringAfter('.', "").trimEnd('0')
                val magnitude = if (fraction.isEmpty()) whole else "$whole.$fraction"
                return if (negative && magnitude != "0") "-$magnitude" else magnitude
            }
        }
    }

    /** True or false: the value of bool fields. */
    public data class Bool(
        /** The value. */
        val value: Boolean,
    ) : Value {
        override fun compareTo(other: Value): Int = value.compareTo(other.expect<Bool>(this).value)
        override fun toString(): String = value.toString()
    }

    /**
     * A list of values of one kind: the value of a [Field.list] input. Lists are never stored or compared, so they
     * can't be a record's field, a filter or a sort key.
     */
    public data class Items(
        /** The items, in order. */
        val values: List<Value>,
    ) : Value {
        override fun compareTo(other: Value): Int = throw IllegalArgumentException("Can't compare lists")
        override fun toString(): String = values.joinToString(prefix = "[", postfix = "]")
    }

    /** A point in time: the value of instant fields. */
    public data class Timestamp(
        /** The instant. */
        val value: Instant,
    ) : Value {
        override fun compareTo(other: Value): Int = value.compareTo(other.expect<Timestamp>(this).value)
        override fun toString(): String = value.toString()
    }
}

private inline fun <reified T : Value> Value.expect(self: Value): T =
    this as? T ?: throw IllegalArgumentException(
        "Can't compare ${self::class.simpleName} with ${this::class.simpleName}",
    )

/** Compares two canonical decimal literals. */
private fun compareDecimals(a: String, b: String): Int {
    val negativeA = a.startsWith('-')
    val negativeB = b.startsWith('-')
    if (negativeA != negativeB) return if (negativeA) -1 else 1
    val magnitude = compareMagnitudes(a.removePrefix("-"), b.removePrefix("-"))
    return if (negativeA) -magnitude else magnitude
}

private fun compareMagnitudes(a: String, b: String): Int {
    val wholeA = a.substringBefore('.')
    val wholeB = b.substringBefore('.')
    if (wholeA.length != wholeB.length) return wholeA.length.compareTo(wholeB.length)
    val whole = wholeA.compareTo(wholeB)
    if (whole != 0) return whole
    val fractionA = a.substringAfter('.', "")
    val fractionB = b.substringAfter('.', "")
    val width = maxOf(fractionA.length, fractionB.length)
    return fractionA.padEnd(width, '0').compareTo(fractionB.padEnd(width, '0'))
}
