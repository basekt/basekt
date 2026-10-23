package io.github.basekt.testing

import io.github.basekt.storage.BlobContent
import io.github.basekt.storage.BlobKey
import io.github.basekt.storage.BlobListing
import io.github.basekt.storage.BlobStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.readByteArray
import kotlinx.io.write
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The tests every [BlobStore] adapter must pass. Subclass it in the adapter's tests and return a fresh, empty store
 * from [createBlobStore]. Stores that implement [BlobListing] are also tested on it.
 *
 * Every key the suite uses starts with [prefix], so a store shared with other data (a real bucket) can run it.
 */
public abstract class BlobStoreContract {
    /** Returns a new store. It may hold other blobs, but none under [prefix]. */
    protected abstract fun createBlobStore(): BlobStore

    /** The first segment of every key the suite writes. */
    protected open val prefix: String = "contract"

    private fun key(name: String) = BlobKey.of("$prefix/$name")

    private fun bytes(size: Int, seed: Int = size) = Random(seed).nextBytes(size)

    private fun source(bytes: ByteArray): RawSource = Buffer().apply { write(bytes) }

    private suspend fun BlobContent.readAll(): ByteArray = use {
        withContext(Dispatchers.IO) {
            val buffer = Buffer()
            while (source.readAtMostTo(buffer, 8192) != -1L) Unit
            buffer.readByteArray()
        }
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runTest(timeout = 2.minutes) { block() }

    /** What is put comes back: bytes, size, content type, and an etag. */
    @Test
    public fun putThenReadBack(): Unit = test {
        val store = createBlobStore()
        val data = bytes(1000)
        val stored = store.put(key("a/b.bin"), source(data), "application/x-test")
        assertEquals(key("a/b.bin"), stored.key)
        assertEquals(1000, stored.size)
        assertEquals("application/x-test", stored.contentType)
        assertTrue(stored.etag.isNotEmpty())
        assertEquals(stored, store.head(key("a/b.bin")))
        val content = assertNotNull(store.open(key("a/b.bin")))
        assertEquals(1000, content.length)
        assertNull(content.range)
        assertContentEquals(data, content.readAll())
    }

    /** An empty blob is a blob. */
    @Test
    public fun emptyBlobs(): Unit = test {
        val store = createBlobStore()
        store.put(key("empty"), source(ByteArray(0)), "text/plain")
        assertEquals(0, store.head(key("empty"))?.size)
        assertContentEquals(ByteArray(0), store.open(key("empty"))!!.readAll())
    }

    /** A blob bigger than any buffer streams through whole. */
    @Test
    public fun largeBlobsStream(): Unit = test {
        val store = createBlobStore()
        val data = bytes(6 * 1024 * 1024 + 13)
        assertEquals(data.size.toLong(), store.put(key("large.bin"), source(data), "application/octet-stream").size)
        assertContentEquals(data, store.open(key("large.bin"))!!.readAll())
    }

    /** A range reads only its bytes; a range outside the blob is refused. */
    @Test
    public fun rangesReadASlice(): Unit = test {
        val store = createBlobStore()
        val data = bytes(500)
        store.put(key("ranged.bin"), source(data), "application/octet-stream")
        for (range in listOf(0L..0L, 10L..19L, 499L..499L, 0L..499L)) {
            val content = assertNotNull(store.open(key("ranged.bin"), range))
            assertEquals(range, content.range)
            assertEquals(range.last - range.first + 1, content.length)
            assertContentEquals(data.copyOfRange(range.first.toInt(), range.last.toInt() + 1), content.readAll(), "$range")
        }
        assertFailsWith<IllegalArgumentException> { store.open(key("ranged.bin"), 400L..500L) }
        assertFailsWith<IllegalArgumentException> { store.open(key("ranged.bin"), 600L..700L) }
    }

    /** A put replaces the blob, and the etag changes with the bytes. */
    @Test
    public fun putReplaces(): Unit = test {
        val store = createBlobStore()
        val first = store.put(key("replaced"), source(bytes(100, seed = 1)), "text/plain")
        val second = store.put(key("replaced"), source(bytes(200, seed = 2)), "application/json")
        assertNotEquals(first.etag, second.etag)
        assertEquals(second.etag, store.head(key("replaced"))?.etag)
        assertEquals("application/json", store.head(key("replaced"))?.contentType)
        assertContentEquals(bytes(200, seed = 2), store.open(key("replaced"))!!.readAll())
    }

    /** A put whose source fails changes nothing: the old blob stays, and a new key stays empty. */
    @Test
    public fun aFailedPutChangesNothing(): Unit = test {
        val store = createBlobStore()
        val data = bytes(300)
        store.put(key("kept"), source(data), "text/plain")
        for (target in listOf(key("kept"), key("never"))) {
            assertFailsWith<IllegalStateException> { store.put(target, failingAfter(bytes(70_000)), "text/plain") }
        }
        assertContentEquals(data, store.open(key("kept"))!!.readAll())
        assertEquals("text/plain", store.head(key("kept"))?.contentType)
        assertNull(store.head(key("never")))
    }

    /** Missing blobs read as null and delete as false; deleting removes the blob. */
    @Test
    public fun missingAndDeleted(): Unit = test {
        val store = createBlobStore()
        assertNull(store.head(key("nothing")))
        assertNull(store.open(key("nothing")))
        assertFalse(store.delete(key("nothing")))
        store.put(key("doomed"), source(bytes(10)), "text/plain")
        assertTrue(store.delete(key("doomed")))
        assertNull(store.head(key("doomed")))
        assertNull(store.open(key("doomed")))
        assertFalse(store.delete(key("doomed")))
    }

    /** A key's prefix isn't a blob. */
    @Test
    public fun prefixesAreNotBlobs(): Unit = test {
        val store = createBlobStore()
        store.put(key("dir/inner.txt"), source(bytes(5)), "text/plain")
        assertNull(store.head(key("dir")))
        assertNull(store.open(key("dir")))
        assertFalse(store.delete(key("dir")))
        assertNotNull(store.head(key("dir/inner.txt")))
    }

    /** Puts to different keys at once all land. */
    @Test
    public fun concurrentPuts(): Unit = test {
        val store = createBlobStore()
        (0 until 16).map { i ->
            async(Dispatchers.IO) { store.put(key("many/$i"), source(bytes(1000 + i, seed = i)), "text/plain") }
        }.awaitAll()
        for (i in 0 until 16) assertContentEquals(bytes(1000 + i, seed = i), store.open(key("many/$i"))!!.readAll())
    }

    /** A [BlobListing] store lists by prefix, in key order, a page at a time. */
    @Test
    public fun listingPagesInKeyOrder(): Unit = test {
        val store = createBlobStore()
        val listing = store as? BlobListing ?: return@test
        val names = listOf("b", "a", "c/1", "c/2", "d")
        for (name in names) store.put(key("listed/$name"), source(bytes(3)), "text/plain")
        store.put(key("other/x"), source(bytes(3)), "text/plain")

        val all = listing.list("$prefix/listed/").map { it.key.value.removePrefix("$prefix/listed/") }
        assertEquals(names.sorted(), all)
        val first = listing.list("$prefix/listed/", limit = 2)
        assertEquals(listOf("a", "b"), first.map { it.key.name })
        val second = listing.list("$prefix/listed/", after = first.last().key, limit = 2)
        assertEquals(listOf("c/1", "c/2"), second.map { it.key.value.removePrefix("$prefix/listed/") })
        assertEquals(3, listing.list("$prefix/listed/", after = second.last().key).single().size)
        assertEquals(listOf("1", "2"), listing.list("$prefix/listed/c/").map { it.key.name })
    }

    /** A source that gives [data] in small reads, then fails. */
    private fun failingAfter(data: ByteArray): RawSource = object : RawSource {
        private var offset = 0

        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            check(offset < data.size) { "The upload broke off." }
            val count = minOf(byteCount.toInt(), 4096, data.size - offset)
            sink.write(data, offset, offset + count)
            offset += count
            return count.toLong()
        }

        override fun close() = Unit
    }
}
