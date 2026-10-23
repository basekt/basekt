package io.github.basekt.storage

/**
 * Where a blob lives in a [BlobStore]: segments joined by `/`, such as `images/2026/desk-lamp.jpg`.
 *
 * Every segment starts with a letter or digit and holds only letters, digits, `.`, `_` and `-`, so a key can never
 * name `.`, `..`, a hidden file or an absolute path. Adapters rely on this to map keys to paths safely. A key is at
 * most [MAX_LENGTH] characters.
 */
@JvmInline
public value class BlobKey private constructor(
    /** The key's text. */
    public val value: String,
) {
    /** The first segment, which `basekt-files` uses as the bucket name. */
    public val first: String get() = value.substringBefore('/')

    /** The last segment. */
    public val name: String get() = value.substringAfterLast('/')

    /** This key with [segment] appended, such as `images` / `a.jpg` → `images/a.jpg`. */
    public operator fun div(segment: String): BlobKey = of("$value/$segment")

    override fun toString(): String = value

    /** Builds and checks keys. */
    public companion object {
        /** The longest key, in characters. */
        public const val MAX_LENGTH: Int = 1024

        private val segment = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

        /** [value] as a key, or `null` if it isn't a valid one. */
        public fun parseOrNull(value: String): BlobKey? =
            if (value.length in 1..MAX_LENGTH && value.split('/').all(segment::matches)) BlobKey(value) else null

        /** [value] as a key. Throws [IllegalArgumentException] with the rules if it isn't a valid one. */
        public fun of(value: String): BlobKey = parseOrNull(value) ?: throw IllegalArgumentException(
            "'$value' isn't a blob key: use 1 to $MAX_LENGTH characters of segments joined by '/', each starting " +
                "with a letter or digit and holding only letters, digits, '.', '_' and '-'.",
        )
    }
}
