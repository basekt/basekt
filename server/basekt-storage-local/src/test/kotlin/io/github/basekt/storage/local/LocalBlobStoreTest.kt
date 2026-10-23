package io.github.basekt.storage.local

import io.github.basekt.storage.BlobKey
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.writeString
import java.nio.file.Files
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalBlobStoreTest {
    private val root = Files.createTempDirectory("basekt-local")

    @Test
    fun `blobs are plain files under the root, with their metadata beside them`() = runTest {
        val store = LocalBlobStore(root)
        store.put(BlobKey.of("docs/readme.txt"), Buffer().apply { writeString("hello") }, "text/plain")
        assertEquals("hello", root.resolve("docs/readme.txt").readText())
        assertTrue(root.resolve(".basekt-meta/docs/readme.txt.properties").readText().contains("contentType=text/plain"))
        assertEquals(emptyList(), root.resolve(".basekt-tmp").listDirectoryEntries())
    }

    @Test
    fun `a file put there by something else is served as bytes`() = runTest {
        val store = LocalBlobStore(root)
        Files.createDirectories(root.resolve("imported"))
        root.resolve("imported/notes.txt").writeText("from elsewhere")
        val info = store.head(BlobKey.of("imported/notes.txt"))!!
        assertEquals("application/octet-stream", info.contentType)
        assertEquals(14, info.size)
        assertEquals(listOf("imported/notes.txt"), store.list("").map { it.key.value })
    }
}
