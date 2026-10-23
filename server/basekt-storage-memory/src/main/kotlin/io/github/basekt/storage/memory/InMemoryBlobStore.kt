package io.github.basekt.storage.memory

import io.github.basekt.storage.BlobContent
import io.github.basekt.storage.BlobInfo
import io.github.basekt.storage.BlobKey
import io.github.basekt.storage.BlobListing
import io.github.basekt.storage.BlobStore
import io.github.basekt.storage.requireRangeWithin
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.readByteArray
import kotlinx.io.write
import java.security.MessageDigest
import java.util.concurrent.ConcurrentSkipListMap
import kotlin.time.Clock

/**
 * A [BlobStore] that keeps every blob in memory, for tests, samples and prototypes. Its contents end with the process.
 * It also implements [BlobListing].
 */
public class InMemoryBlobStore(private val clock: Clock = Clock.System) : BlobStore, BlobListing {
    private class Stored(val info: BlobInfo, val bytes: ByteArray)

    private val blobs = ConcurrentSkipListMap<String, Stored>()

    override suspend fun put(key: BlobKey, content: RawSource, contentType: String): BlobInfo {
        val buffer = Buffer()
        while (content.readAtMostTo(buffer, CHUNK) != -1L) Unit
        val bytes = buffer.readByteArray()
        val info = BlobInfo(key, bytes.size.toLong(), contentType, etag(bytes), clock.now())
        blobs[key.value] = Stored(info, bytes)
        return info
    }

    override suspend fun head(key: BlobKey): BlobInfo? = blobs[key.value]?.info

    override suspend fun open(key: BlobKey, range: LongRange?): BlobContent? {
        val stored = blobs[key.value] ?: return null
        requireRangeWithin(range, stored.info.size)
        val from = range?.first?.toInt() ?: 0
        val to = range?.let { it.last.toInt() + 1 } ?: stored.bytes.size
        return BlobContent(stored.info, range, Buffer().apply { write(stored.bytes, from, to) })
    }

    override suspend fun delete(key: BlobKey): Boolean = blobs.remove(key.value) != null

    override suspend fun list(prefix: String, after: BlobKey?, limit: Int): List<BlobInfo> {
        val tail = if (after == null || after.value < prefix) blobs.tailMap(prefix, true) else blobs.tailMap(after.value, false)
        return tail.entries.asSequence()
            .takeWhile { it.key.startsWith(prefix) }
            .take(limit)
            .map { it.value.info }
            .toList()
    }

    private fun etag(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(32)

    private companion object {
        const val CHUNK = 64L * 1024
    }
}
