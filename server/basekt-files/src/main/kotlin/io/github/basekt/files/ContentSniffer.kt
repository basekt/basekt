package io.github.basekt.files

/**
 * Works out a file's media type from its first bytes, so an upload can't choose how it is served by lying in its
 * `Content-Type`. It knows common images, PDF, audio, video, archives, markup and text.
 */
internal object ContentSniffer {
    /** How many leading bytes [sniff] looks at. */
    const val PREFIX = 512

    private val dangerous = setOf(
        "text/html", "application/xhtml+xml", "image/svg+xml", "text/xml", "application/xml",
        "text/javascript", "application/javascript", "application/ecmascript", "text/ecmascript",
    )

    /** Types that render or run in a browser, so they're never served inline and never trusted from a client. */
    fun isDangerous(type: String): Boolean = type in dangerous

    /**
     * The media type of a file that starts with [head]. When the bytes only say "text" or "binary", [declared] is
     * used if it is a type of that kind that can't run in a browser, such as `text/csv` or `application/json`.
     */
    fun sniff(head: ByteArray, declared: String?): String {
        val sniffed = magic(head) ?: if (looksLikeText(head)) markup(head) ?: TEXT else BINARY
        val claimed = declared?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && !isDangerous(it) }
        return when {
            claimed == null -> sniffed
            sniffed == TEXT && (claimed.startsWith("text/") || claimed in textApplicationTypes) -> claimed
            sniffed == BINARY && !claimed.startsWith("text/") && magicTypes.none { it == claimed } -> claimed
            else -> sniffed
        }
    }

    private const val TEXT = "text/plain"
    private const val BINARY = "application/octet-stream"
    private val textApplicationTypes = setOf("application/json", "application/x-ndjson", "application/yaml")

    private val signatures: List<Pair<String, (ByteArray) -> Boolean>> = listOf(
        "image/png" to { b -> b.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) },
        "image/jpeg" to { b -> b.startsWith(0xFF, 0xD8, 0xFF) },
        "image/gif" to { b -> b.startsWithText("GIF87a") || b.startsWithText("GIF89a") },
        "image/webp" to { b -> b.startsWithText("RIFF") && b.textAt(8, "WEBP") },
        "image/avif" to { b -> b.textAt(4, "ftypavif") || b.textAt(4, "ftypavis") },
        "image/bmp" to { b -> b.startsWithText("BM") && b.size > 14 },
        "image/x-icon" to { b -> b.startsWith(0x00, 0x00, 0x01, 0x00) },
        "application/pdf" to { b -> b.startsWithText("%PDF-") },
        "audio/mpeg" to { b -> b.startsWithText("ID3") || (b.size > 1 && b[0] == 0xFF.toByte() && (b[1].toInt() and 0xE0) == 0xE0) },
        "audio/wav" to { b -> b.startsWithText("RIFF") && b.textAt(8, "WAVE") },
        "audio/ogg" to { b -> b.startsWithText("OggS") },
        "video/webm" to { b -> b.startsWith(0x1A, 0x45, 0xDF, 0xA3) },
        "video/mp4" to { b -> b.textAt(4, "ftyp") },
        "application/zip" to { b -> b.startsWith(0x50, 0x4B, 0x03, 0x04) || b.startsWith(0x50, 0x4B, 0x05, 0x06) },
        "application/gzip" to { b -> b.startsWith(0x1F, 0x8B) },
    )
    private val magicTypes = signatures.map { it.first }

    private fun magic(head: ByteArray): String? = signatures.firstOrNull { (_, matches) -> matches(head) }?.first

    /** HTML, SVG and XML, which must never be served as anything a browser would render. */
    private fun markup(head: ByteArray): String? {
        val text = head.decodeToString().trimStart('﻿', ' ', '\t', '\r', '\n').lowercase()
        if (!text.startsWith("<")) return null
        return when {
            "<svg" in text -> "image/svg+xml"
            text.startsWith("<?xml") -> "application/xml"
            // Any other text that opens with a tag may be read as HTML by some browser, so treat it as HTML.
            else -> "text/html"
        }
    }

    /** No NUL bytes and valid UTF-8, allowing a code point cut off at the end of the prefix. */
    private fun looksLikeText(head: ByteArray): Boolean {
        if (head.isEmpty()) return true
        if (head.any { it == 0.toByte() }) return false
        val decoder = Charsets.UTF_8.newDecoder()
        for (cut in 0..3) {
            if (head.size - cut <= 0) break
            val ok = runCatching { decoder.reset().decode(java.nio.ByteBuffer.wrap(head, 0, head.size - cut)) }.isSuccess
            if (ok) return true
        }
        return false
    }

    private fun ByteArray.startsWith(vararg bytes: Int): Boolean =
        size >= bytes.size && bytes.indices.all { this[it] == bytes[it].toByte() }

    private fun ByteArray.startsWithText(text: String): Boolean = textAt(0, text)

    private fun ByteArray.textAt(offset: Int, text: String): Boolean =
        size >= offset + text.length && text.indices.all { this[offset + it] == text[it].code.toByte() }
}
