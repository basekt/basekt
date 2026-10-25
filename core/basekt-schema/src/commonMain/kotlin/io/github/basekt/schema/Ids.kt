package io.github.basekt.schema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where the [Resource.ID]s of a resource's records come from, and what they may look like. An id never changes once a
 * record is created.
 */
@Serializable
public data class IdSpec(
    /** Who chooses a new record's id. */
    val source: IdSource = IdSource.GENERATED,
    /**
     * Checks on an id that a create gives, beyond what every id must be ([problemWith]): [Rule.Length],
     * [Rule.Pattern] and [Rule.Custom] rules, such as the shape of a username or a reverse-DNS name.
     */
    val rules: List<Rule> = emptyList(),
    /**
     * For [IdSource.KEY]: the fields, at least two, whose values make the id, in order. Each one is required on create
     * and can't change afterwards, so no two records hold the same values in all of them.
     */
    val key: List<String> = emptyList(),
) {
    /**
     * Returns the id that [record]'s [key] fields make, or `null` if one of them has no value. Each value is written as
     * text, with `%`, `~`, `/` and control characters percent-encoded, and the parts are joined with [KEY_SEPARATOR]:
     * the key `(alice, item-7)` makes `alice~item-7`.
     */
    public fun keyOf(record: Record): String? {
        val parts = key.map { field ->
            when (val value = record[field]) {
                is Value.Text -> value.value
                is Value.Integer -> value.value.toString()
                else -> return null
            }
        }
        return keyOf(parts)
    }

    /** Ids and key ids. */
    public companion object {
        /** The most characters an id may have. A data source may store fewer; see its documentation. */
        public const val MAX_LENGTH: Int = 256

        /** What joins the parts of a [key] id. */
        public const val KEY_SEPARATOR: Char = '~'

        /** Returns the id that the key values [parts] make, as [IdSpec.keyOf] describes. */
        public fun keyOf(parts: List<String>): String = parts.joinToString(KEY_SEPARATOR.toString()) { part ->
            buildString {
                for (char in part) {
                    if (char == '%' || char == KEY_SEPARATOR || char == '/' || char.isISOControl()) {
                        append('%').append(char.code.toString(16).uppercase().padStart(2, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }

        /** Returns the key values a [keyOf] id was made from: the reverse of [keyOf]. */
        public fun partsOf(id: String): List<String> = id.split(KEY_SEPARATOR).map { part ->
            buildString {
                var i = 0
                while (i < part.length) {
                    val code = if (part[i] == '%' && i + 3 <= part.length) part.substring(i + 1, i + 3).toIntOrNull(16) else null
                    if (code != null) {
                        append(code.toChar())
                        i += 3
                    } else {
                        append(part[i])
                        i++
                    }
                }
            }
        }

        /**
         * Returns why [id] can't be an id, or `null` if it can. Every data source stores an id that is 1 to
         * [MAX_LENGTH] characters long, holds no `/` or control characters, isn't `.` or `..`, and doesn't both start
         * and end with `__`.
         */
        public fun problemWith(id: String): String? = when {
            id.isEmpty() -> "An id can't be empty."
            id.length > MAX_LENGTH -> "An id can have at most $MAX_LENGTH characters."
            id == "." || id == ".." -> "An id can't be '.' or '..'."
            '/' in id -> "An id can't hold '/'."
            id.any { it.isISOControl() } -> "An id can't hold control characters."
            id.length >= 4 && id.startsWith("__") && id.endsWith("__") -> "An id can't both start and end with '__'."
            else -> null
        }
    }
}

/** Who chooses a new record's [Resource.ID]. See [IdSpec]. */
@Serializable
public enum class IdSource {
    /** The data source makes one, unless the create gives one. */
    @SerialName("generated") GENERATED,

    /**
     * The client gives one on every create, such as a username, a slug or a reverse-DNS name. A create without an id
     * is refused, and so is one whose id another record has.
     */
    @SerialName("natural") NATURAL,

    /**
     * The server makes it from the [IdSpec.key] fields, so a record is unique per those fields together and is
     * addressed by their values. A client can't give one.
     */
    @SerialName("key") KEY,
}

/** What deleting a record does to the records that [Relation.BelongsTo] it. */
@Serializable
public enum class OnDelete {
    /** The delete is refused while any record belongs to it. */
    @SerialName("restrict") RESTRICT,

    /** Those records are deleted too, in the same transaction, and so on down. */
    @SerialName("cascade") CASCADE,

    /** Their reference field is cleared, in the same transaction. The field can't be required. */
    @SerialName("setNull") SET_NULL,
}
