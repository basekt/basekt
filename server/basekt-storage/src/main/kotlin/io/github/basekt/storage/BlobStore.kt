package io.github.basekt.storage

import kotlinx.io.RawSource
import kotlin.time.Instant

/**
 * Where files' bytes live. Every adapter (in memory, local disk, S3...) implements this interface and passes the
 * `BlobStoreContract` suite in `testing:basekt-testing`.
 *
 * The contract:
 * - Bytes are streamed both ways; a store never needs a whole blob in memory, except the in-memory one.
 * - A [put] replaces any blob at the key all at once: a reader sees the old blob or the new one, never a mix, and a
 *   put that fails leaves the old blob as it was.
 * - Stores don't check what they're given (size limits, content types, permissions); `basekt-files` does.
 * - Every function is safe to call concurrently.
 *
 * Optional capabilities, such as [BlobListing] and [PresignedUrls], are separate interfaces: check for them with
 * `store as? BlobListing`.
 */
public interface BlobStore {
    /**
     * Reads [content] to its end and stores it at [key] with [contentType], replacing any blob there, and returns
     * what was stored. It doesn't close [content]. If reading [content] throws, the exception propagates and
     * nothing at [key] changes.
     */
    public suspend fun put(key: BlobKey, content: RawSource, contentType: String): BlobInfo

    /** What is stored at [key], or `null` if nothing is. */
    public suspend fun head(key: BlobKey): BlobInfo?

    /**
     * Opens the blob at [key] for reading, or returns `null` if there is none. With a [range], only those bytes are
     * read; it must lie within the blob (`0 <= first <= last < size`), or this throws [IllegalArgumentException].
     * Close the returned [BlobContent] when done.
     */
    public suspend fun open(key: BlobKey, range: LongRange? = null): BlobContent?

    /** Deletes the blob at [key]. Returns `false` if there was none. */
    public suspend fun delete(key: BlobKey): Boolean
}

/**
 * What a [BlobStore] holds at a key.
 *
 * @property key where the blob is.
 * @property size its length in bytes.
 * @property contentType the media type it was stored with, such as `image/png`.
 * @property etag a token that changes whenever the bytes do, for HTTP caching. Its format is the store's own.
 * @property lastModified when it was last put.
 */
public data class BlobInfo(
    val key: BlobKey,
    val size: Long,
    val contentType: String,
    val etag: String,
    val lastModified: Instant,
)

/**
 * An open blob: its [info], and [source] to read its bytes from, only those of [range] when one was asked for.
 * Closing it closes [source].
 */
public class BlobContent(
    /** What the blob is. */
    public val info: BlobInfo,
    /** The bytes read, or `null` for the whole blob. */
    public val range: LongRange?,
    /** The bytes. Reading may block, so read on an IO dispatcher. */
    public val source: RawSource,
) : AutoCloseable {
    /** How many bytes [source] gives. */
    public val length: Long get() = range?.let { it.last - it.first + 1 } ?: info.size

    override fun close() {
        source.close()
    }
}

/** Checks a [range] against a blob of [size] bytes, as [BlobStore.open] requires. For adapters. */
public fun requireRangeWithin(range: LongRange?, size: Long) {
    if (range == null) return
    require(range.first in 0..range.last && range.last < size) {
        "The range ${range.first}..${range.last} isn't within a blob of $size bytes."
    }
}
