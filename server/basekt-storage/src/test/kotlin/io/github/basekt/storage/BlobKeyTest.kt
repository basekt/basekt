package io.github.basekt.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BlobKeyTest {
    @Test
    fun `keys are segments of safe characters`() {
        val key = BlobKey.of("images/2026/desk-lamp_v2.JPG")
        assertEquals("images", key.first)
        assertEquals("desk-lamp_v2.JPG", key.name)
        assertEquals(BlobKey.of("images/a/b.png"), BlobKey.of("images") / "a" / "b.png")
    }

    @Test
    fun `nothing that could escape a directory is a key`() {
        for (bad in listOf("", "/abs", "a//b", "a/", "..", "a/../b", "./a", ".hidden", "a/.meta", "a\b", "a b", "ä", "a:b", "x".repeat(1025))) {
            assertNull(BlobKey.parseOrNull(bad), bad)
        }
        val error = assertFailsWith<IllegalArgumentException> { BlobKey.of("../etc/passwd") }
        assertEquals(true, error.message?.contains("isn't a blob key"))
    }
}
