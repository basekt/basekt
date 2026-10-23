package io.github.basekt.storage

import kotlin.time.Duration

/**
 * An optional [BlobStore] capability: listing what is stored under a prefix, in key order. `basekt-files` uses it to
 * sweep uploads that were never attached to a record.
 */
public interface BlobListing {
    /**
     * Returns up to [limit] blobs whose keys start with [prefix] and sort after [after], in ascending key order.
     * Pass the last key of a page as [after] to read the next one.
     */
    public suspend fun list(prefix: String, after: BlobKey? = null, limit: Int = 1000): List<BlobInfo>
}

/**
 * An optional [BlobStore] capability: URLs a browser can use to read a blob straight from the store, so the bytes
 * don't pass through the server. Checks happen when the URL is made; anyone holding it can read until it expires.
 */
public interface PresignedUrls {
    /**
     * A URL that reads the blob at [key] for [validFor], answering with [contentDisposition] when it isn't `null`,
     * or `null` if the store can't make one now.
     */
    public suspend fun downloadUrl(key: BlobKey, validFor: Duration, contentDisposition: String? = null): String?
}
