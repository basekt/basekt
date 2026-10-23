package io.github.basekt.storage.s3

import java.net.URI
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/** AWS Signature Version 4 for S3: signed request headers and presigned URLs. */
internal class SigV4(
    private val accessKey: String,
    private val secretKey: String,
    private val region: String,
    private val service: String = "s3",
) {
    /**
     * The `Authorization` header for a request to [uri] with [headers] (names in any case; `host` is added from the
     * URI) and a body whose SHA-256 is [payloadHash]. [headers] must already hold `x-amz-date` and
     * `x-amz-content-sha256`.
     */
    fun authorization(method: String, uri: URI, headers: Map<String, String>, payloadHash: String, now: Instant): String {
        val all = (headers.mapKeys { it.key.lowercase() } + ("host" to hostOf(uri))).toSortedMap()
        val signedHeaders = all.keys.joinToString(";")
        val canonical = canonicalRequest(method, uri, canonicalQuery(uri), all, signedHeaders, payloadHash)
        val signature = sign(canonical, now)
        return "$ALGORITHM Credential=$accessKey/${scope(now)}, SignedHeaders=$signedHeaders, Signature=$signature"
    }

    /** A presigned URL that performs [method] on [uri] for [expiresSeconds], with [extraQuery] signed in. */
    fun presign(method: String, uri: URI, expiresSeconds: Long, now: Instant, extraQuery: Map<String, String> = emptyMap()): URI {
        val query = extraQuery + mapOf(
            "X-Amz-Algorithm" to ALGORITHM,
            "X-Amz-Credential" to "$accessKey/${scope(now)}",
            "X-Amz-Date" to amzDate(now),
            "X-Amz-Expires" to expiresSeconds.toString(),
            "X-Amz-SignedHeaders" to "host",
        )
        val canonicalQuery = query.toSortedMap().entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        val canonical = canonicalRequest(method, uri, canonicalQuery, sortedMapOf("host" to hostOf(uri)), "host", UNSIGNED_PAYLOAD)
        val signature = sign(canonical, now)
        return URI("${uri.scheme}://${uri.rawAuthority}${uri.rawPath}?$canonicalQuery&X-Amz-Signature=$signature")
    }

    private fun canonicalRequest(
        method: String,
        uri: URI,
        query: String,
        headers: Map<String, String>,
        signedHeaders: String,
        payloadHash: String,
    ): String = buildString {
        append(method).append('\n')
        append(uri.rawPath.ifEmpty { "/" }).append('\n')
        append(query).append('\n')
        for ((name, value) in headers) append(name).append(':').append(value.trim().replace(Regex("\\s+"), " ")).append('\n')
        append('\n')
        append(signedHeaders).append('\n')
        append(payloadHash)
    }

    private fun sign(canonicalRequest: String, now: Instant): String {
        val stringToSign = "$ALGORITHM\n${amzDate(now)}\n${scope(now)}\n${sha256Hex(canonicalRequest.toByteArray())}"
        val date = hmac("AWS4$secretKey".toByteArray(), dateStamp(now))
        val signingKey = hmac(hmac(hmac(date, region), service), "aws4_request")
        return hmac(signingKey, stringToSign).hex()
    }

    private fun scope(now: Instant) = "${dateStamp(now)}/$region/$service/aws4_request"

    companion object {
        const val ALGORITHM = "AWS4-HMAC-SHA256"
        const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"

        private val amzDateFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        private val dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

        /** The `x-amz-date` of [now], such as `20130524T000000Z`. */
        fun amzDate(now: Instant): String = amzDateFormat.format(now.toJavaInstant())

        private fun dateStamp(now: Instant): String = dateFormat.format(now.toJavaInstant())

        /** The query of [uri] in canonical form: decoded, then each name and value encoded, sorted by name. */
        fun canonicalQuery(uri: URI): String {
            val raw = uri.rawQuery ?: return ""
            return raw.split('&').filter { it.isNotEmpty() }.map { pair ->
                val name = decode(pair.substringBefore('='))
                val value = decode(pair.substringAfter('=', ""))
                encode(name) to encode(value)
            }.sortedWith(compareBy({ it.first }, { it.second })).joinToString("&") { "${it.first}=${it.second}" }
        }

        /** URI-encodes [text] as SigV4 wants: everything but `A-Za-z0-9-_.~`, with `/` too unless [keepSlash]. */
        fun encode(text: String, keepSlash: Boolean = false): String = buildString {
            for (byte in text.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt().toChar()
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~' || (keepSlash && c == '/')) append(c)
                else append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }

        private fun decode(text: String): String = java.net.URLDecoder.decode(text.replace("+", "%2B"), Charsets.UTF_8)

        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

        private fun hostOf(uri: URI): String = if (uri.port == -1) uri.host else "${uri.host}:${uri.port}"

        private fun hmac(key: ByteArray, data: String): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())

        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    }
}
