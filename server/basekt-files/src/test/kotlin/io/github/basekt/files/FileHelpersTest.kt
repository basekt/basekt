package io.github.basekt.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileHelpersTest {
    @Test
    fun `file names become safe key segments`() {
        assertEquals("Desk-lamp-1-.JPG", safeName("Desk lamp (1).JPG"))
        assertEquals("passwd", safeName("../../etc/passwd"))
        assertEquals("evil.exe", safeName("C:\\Users\\x\\evil.exe"))
        assertEquals("hidden", safeName(".hidden"))
        assertEquals("file", safeName(null))
        assertEquals("file", safeName("..."))
        assertEquals(100, safeName("a".repeat(300)).length)
    }

    @Test
    fun `ranges are read within the file`() {
        assertEquals(0L..9L, parseRange("bytes=0-9", 100))
        assertEquals(90L..99L, parseRange("bytes=90-", 100))
        assertEquals(95L..99L, parseRange("bytes=-5", 100))
        assertEquals(0L..99L, parseRange("bytes=-500", 100))
        assertEquals(50L..99L, parseRange("bytes=50-500", 100))
        assertEquals(0L..99L, parseRange("bytes=0-1,5-9", 100))
        assertNull(parseRange("bytes=100-", 100))
        assertNull(parseRange("bytes=9-3", 100))
        assertNull(parseRange("items=0-1", 100))
        assertNull(parseRange("bytes=0-0", 0))
    }

    @Test
    fun `types come from the bytes`() {
        assertEquals("image/png", ContentSniffer.sniff(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), "text/plain"))
        assertEquals("image/jpeg", ContentSniffer.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()), null))
        assertEquals("application/pdf", ContentSniffer.sniff("%PDF-1.7".toByteArray(), "image/png"))
        assertEquals("image/svg+xml", ContentSniffer.sniff("<?xml version=\"1.0\"?><svg/>".toByteArray(), "image/png"))
        assertEquals("text/html", ContentSniffer.sniff("  <b>hi</b>".toByteArray(), "text/plain"))
        assertEquals("text/plain", ContentSniffer.sniff("hello".toByteArray(), "image/png"))
        assertEquals("text/csv", ContentSniffer.sniff("a,b".toByteArray(), "text/csv"))
        assertEquals("application/json", ContentSniffer.sniff("{\"a\":1}".toByteArray(), "application/json"))
        assertEquals("text/plain", ContentSniffer.sniff("x".toByteArray(), "text/html"))
        assertEquals("application/octet-stream", ContentSniffer.sniff(byteArrayOf(0, 1, 2, 3), "image/png"))
        assertEquals("application/x-custom", ContentSniffer.sniff(byteArrayOf(0, 1, 2, 3), "application/x-custom"))
    }
}
