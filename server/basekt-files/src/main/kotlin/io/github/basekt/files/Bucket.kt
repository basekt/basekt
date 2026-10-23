package io.github.basekt.files

import io.github.basekt.schema.Access
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * A named group of files with its own limits and permissions. Its files are stored under keys that start with
 * [name], such as `images/3f1c…/lamp.jpg`.
 *
 * @property name the bucket's name: the first segment of its keys and of its URLs.
 * @property maxBytes the largest file it takes, in bytes.
 * @property accept the media types it takes, such as `image/png`, or a family such as `image/` followed by a star.
 *   Empty takes any type.
 * @property read who may download its files.
 * @property upload who may upload to it.
 * @property delete who may delete its files.
 * @property deleteUnattachedAfter how old an upload no file field points to may get before it is deleted, or `null`
 *   to keep such uploads. It applies only to buckets that file fields use.
 */
public data class Bucket(
    val name: String,
    val maxBytes: Long,
    val accept: Set<String>,
    val read: Access,
    val upload: Access,
    val delete: Access,
    val deleteUnattachedAfter: Duration? = 24.hours,
) {
    /** Whether [contentType] is one the bucket takes. */
    public fun accepts(contentType: String): Boolean = accept.isEmpty() || accept.any { pattern ->
        if (pattern.endsWith("/*")) contentType.startsWith(pattern.dropLast(1)) else contentType == pattern
    }
}

/** Sets up a [Bucket]: `bucket("images") { maxBytes = 5 * MEGABYTE; accept("image/png"); read(everyone); write("staff") }`. */
public class BucketBuilder internal constructor(internal val name: String, defaultMaxBytes: Long) {
    private val accept = linkedSetOf<String>()
    private var read: Access = Access.Nobody
    private var upload: Access = Access.Nobody
    private var delete: Access = Access.Nobody

    /** The largest file the bucket takes, in bytes. The plugin's [BaseKtFilesConfig.maxBytes] by default. */
    public var maxBytes: Long = defaultMaxBytes

    /**
     * How old an upload no file field points to may get before it is deleted: uploads abandoned in a form that was
     * never saved. 24 hours by default; `null` keeps them. It applies only to buckets that file fields use.
     */
    public var deleteUnattachedAfter: Duration? = 24.hours

    /** Anyone, including anonymous callers. */
    public val everyone: Access get() = Access.Everyone

    /** Any authenticated caller. */
    public val authenticated: Access get() = Access.Authenticated

    /** Takes these media types only, such as `image/png`, or a whole family: `image/` followed by a star. */
    public fun accept(vararg types: String) {
        for (type in types) {
            require(type.matches(mediaTypePattern)) { "'$type' isn't a media type like image/png or image/*." }
            accept += type.lowercase()
        }
    }

    /** Who may download files. */
    public fun read(access: Access) {
        read = access
    }

    /** Callers with any of [roles] may download files. */
    public fun read(vararg roles: String): Unit = read(Access.Roles(roles.toSet()))

    /** Who may upload files. */
    public fun upload(access: Access) {
        upload = access
    }

    /** Callers with any of [roles] may upload files. */
    public fun upload(vararg roles: String): Unit = upload(Access.Roles(roles.toSet()))

    /** Who may delete files. */
    public fun delete(access: Access) {
        delete = access
    }

    /** Callers with any of [roles] may delete files. */
    public fun delete(vararg roles: String): Unit = delete(Access.Roles(roles.toSet()))

    /** Who may upload and delete files. */
    public fun write(access: Access) {
        upload = access
        delete = access
    }

    /** Callers with any of [roles] may upload and delete files. */
    public fun write(vararg roles: String): Unit = write(Access.Roles(roles.toSet()))

    internal fun build(): Bucket {
        require(maxBytes >= 1) { "Bucket '$name' needs a maxBytes of at least 1, not $maxBytes." }
        require(deleteUnattachedAfter?.isPositive() != false) { "Bucket '$name' needs a positive deleteUnattachedAfter, or null." }
        return Bucket(name, maxBytes, accept.toSet(), read, upload, delete, deleteUnattachedAfter)
    }

    private companion object {
        val mediaTypePattern = Regex("[a-zA-Z0-9][a-zA-Z0-9.+-]*/(\\*|[a-zA-Z0-9][a-zA-Z0-9.+-]*)")
    }
}

/** A kibibyte, for sizes such as `512 * KILOBYTE`. */
public const val KILOBYTE: Long = 1024

/** A mebibyte, for sizes such as `5 * MEGABYTE`. */
public const val MEGABYTE: Long = 1024 * 1024
