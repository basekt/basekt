package io.github.basekt.storage.s3

import io.github.basekt.storage.BlobContent
import io.github.basekt.storage.BlobInfo
import io.github.basekt.storage.BlobKey
import io.github.basekt.storage.BlobListing
import io.github.basekt.storage.BlobStore
import io.github.basekt.storage.PresignedUrls
import io.github.basekt.storage.requireRangeWithin
import kotlinx.coroutines.future.await
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.asSource
import kotlinx.io.readByteArray
import org.w3c.dom.Element
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

/**
 * Where an [S3BlobStore] keeps blobs: an S3 bucket, or a bucket on any S3-compatible service such as MinIO or
 * Cloudflare R2.
 *
 * @property endpoint the service's URL, such as `https://s3.eu-west-1.amazonaws.com`, `http://localhost:9000` for
 *   MinIO, or `https://<account>.r2.cloudflarestorage.com` for R2.
 * @property bucket the bucket's name. It must exist.
 * @property region the region requests are signed for; `auto` for R2, `us-east-1` for MinIO by default.
 * @property accessKey the access key id.
 * @property secretKey the secret access key.
 * @property pathStyle whether the bucket goes in the path (`endpoint/bucket/key`), as MinIO wants, or in the host
 *   (`bucket.endpoint/key`), as AWS prefers.
 * @property prefix a key prefix inside the bucket, such as `basekt/`, so the store can share a bucket.
 */
public data class S3Settings(
    val endpoint: String,
    val bucket: String,
    val region: String,
    val accessKey: String,
    val secretKey: String,
    val pathStyle: Boolean = true,
    val prefix: String = "",
) {
    init {
        require(endpoint.startsWith("http://") || endpoint.startsWith("https://")) { "The S3 endpoint must be an http(s) URL, not '$endpoint'." }
        require(bucket.matches(Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]"))) { "'$bucket' isn't an S3 bucket name." }
        require(prefix.isEmpty() || (prefix.endsWith("/") && BlobKey.parseOrNull(prefix.dropLast(1)) != null)) {
            "The prefix must be empty or key segments ending with '/', such as 'basekt/', not '$prefix'."
        }
    }

    override fun toString(): String = "S3Settings(endpoint=$endpoint, bucket=$bucket, region=$region, accessKey=$accessKey, prefix=$prefix)"
}

/**
 * A [BlobStore] on S3 or an S3-compatible service, signed with Signature V4 over the JDK's HTTP client, so it needs
 * no SDK. It also implements [BlobListing] and [PresignedUrls].
 *
 * Puts of up to [partSize] bytes are one request; larger ones are multipart uploads of [partSize] parts, aborted if
 * reading the content fails, so a failed put leaves the old object. In [list], content types read as
 * `application/octet-stream`, since S3's listing doesn't carry them.
 */
public class S3BlobStore(
    private val settings: S3Settings,
    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
    private val clock: Clock = Clock.System,
    private val partSize: Int = 8 * 1024 * 1024,
) : BlobStore, BlobListing, PresignedUrls {
    private val signer = SigV4(settings.accessKey, settings.secretKey, settings.region)
    private val endpoint = settings.endpoint.trimEnd('/')

    init {
        require(partSize >= 5 * 1024 * 1024) { "S3 parts are at least 5 MiB." }
    }

    override suspend fun put(key: BlobKey, content: RawSource, contentType: String): BlobInfo {
        val first = readUpTo(content, partSize)
        val second = if (first.size < partSize) ByteArray(0) else readUpTo(content, partSize)
        if (second.isEmpty()) {
            send("PUT", objectUri(key), first, mapOf("Content-Type" to contentType)).expect(200)
        } else {
            multipart(key, content, contentType, first, second)
        }
        return checkNotNull(head(key)) { "S3 lost '$key' right after storing it." }
    }

    private suspend fun multipart(key: BlobKey, content: RawSource, contentType: String, first: ByteArray, second: ByteArray) {
        val created = send("POST", objectUri(key, "uploads" to ""), ByteArray(0), mapOf("Content-Type" to contentType)).expect(200)
        val uploadId = xml(created.body()).text("UploadId")
        try {
            val etags = mutableListOf<String>()
            var part = first
            var next: ByteArray? = second
            while (part.isNotEmpty()) {
                val number = etags.size + 1
                val response = send("PUT", objectUri(key, "partNumber" to "$number", "uploadId" to uploadId), part).expect(200)
                etags += response.headers().firstValue("ETag").orElseThrow()
                part = next ?: readUpTo(content, partSize)
                next = null
            }
            val complete = buildString {
                append("<CompleteMultipartUpload>")
                etags.forEachIndexed { i, etag -> append("<Part><PartNumber>${i + 1}</PartNumber><ETag>").append(escape(etag)).append("</ETag></Part>") }
                append("</CompleteMultipartUpload>")
            }
            val done = send("POST", objectUri(key, "uploadId" to uploadId), complete.toByteArray(), mapOf("Content-Type" to "application/xml")).expect(200)
            // S3 can answer 200 with an error document when completing fails.
            if (String(done.body()).contains("<Error>")) throw S3Exception(200, String(done.body()))
        } catch (e: Throwable) {
            runCatching { send("DELETE", objectUri(key, "uploadId" to uploadId), ByteArray(0)) }
            throw e
        }
    }

    override suspend fun head(key: BlobKey): BlobInfo? {
        val response = send("HEAD", objectUri(key), ByteArray(0))
        if (response.statusCode() == 404) return null
        response.expect(200)
        val headers = response.headers()
        return BlobInfo(
            key = key,
            size = headers.firstValueAsLong("Content-Length").orElse(0),
            contentType = headers.firstValue("Content-Type").orElse("application/octet-stream"),
            etag = headers.firstValue("ETag").orElse("").trim('"'),
            lastModified = headers.firstValue("Last-Modified").map(::httpDate).orElse(clock.now()),
        )
    }

    override suspend fun open(key: BlobKey, range: LongRange?): BlobContent? {
        if (range != null) {
            val info = head(key) ?: return null
            requireRangeWithin(range, info.size)
        }
        val headers = range?.let { mapOf("Range" to "bytes=${it.first}-${it.last}") }.orEmpty()
        val response = sendStreaming("GET", objectUri(key), headers)
        val status = response.statusCode()
        if (status == 404) {
            response.body().close()
            return null
        }
        if (status != 200 && status != 206) {
            val body = response.body().use { it.readAllBytes() }
            throw S3Exception(status, String(body))
        }
        val responseHeaders = response.headers()
        val size = responseHeaders.firstValue("Content-Range").map { it.substringAfterLast('/').toLong() }
            .orElseGet { responseHeaders.firstValueAsLong("Content-Length").orElse(0) }
        val info = BlobInfo(
            key = key,
            size = size,
            contentType = responseHeaders.firstValue("Content-Type").orElse("application/octet-stream"),
            etag = responseHeaders.firstValue("ETag").orElse("").trim('"'),
            lastModified = responseHeaders.firstValue("Last-Modified").map(::httpDate).orElse(clock.now()),
        )
        return BlobContent(info, range, response.body().asSource())
    }

    override suspend fun delete(key: BlobKey): Boolean {
        // S3 answers a delete of a missing object with 204 too, so look first.
        if (head(key) == null) return false
        send("DELETE", objectUri(key), ByteArray(0)).expect(204, 200)
        return true
    }

    override suspend fun list(prefix: String, after: BlobKey?, limit: Int): List<BlobInfo> {
        val query = buildList {
            add("list-type" to "2")
            add("max-keys" to "${limit.coerceIn(1, 1000)}")
            add("prefix" to settings.prefix + prefix)
            if (after != null && after.value >= prefix) add("start-after" to settings.prefix + after.value)
        }
        val response = send("GET", bucketUri(*query.toTypedArray()), ByteArray(0)).expect(200)
        val document = xml(response.body())
        val contents = document.getElementsByTagName("Contents")
        return (0 until contents.length).mapNotNull { index ->
            val item = contents.item(index) as Element
            val key = BlobKey.parseOrNull(item.text("Key").removePrefix(settings.prefix)) ?: return@mapNotNull null
            BlobInfo(key, item.text("Size").toLong(), "application/octet-stream", item.text("ETag").trim('"'), Instant.parse(item.text("LastModified")))
        }.take(limit)
    }

    override suspend fun downloadUrl(key: BlobKey, validFor: Duration, contentDisposition: String?): String {
        val seconds = validFor.inWholeSeconds.coerceIn(1, 7 * 24 * 3600)
        val extra = contentDisposition?.let { mapOf("response-content-disposition" to it) }.orEmpty()
        return signer.presign("GET", objectUri(key), seconds, clock.now(), extra).toString()
    }

    private fun objectUri(key: BlobKey, vararg query: Pair<String, String>): URI =
        uri("/" + SigV4.encode(settings.prefix + key.value, keepSlash = true), query)

    private fun bucketUri(vararg query: Pair<String, String>): URI = uri("/", query)

    private fun uri(path: String, query: Array<out Pair<String, String>>): URI {
        val base = URI(endpoint)
        val authority = if (settings.pathStyle) base.rawAuthority else "${settings.bucket}.${base.rawAuthority}"
        val fullPath = (if (settings.pathStyle) "/${settings.bucket}" else "") + path.let { if (settings.pathStyle && it == "/") "" else it }
        val text = query.joinToString("&") { (k, v) -> if (v.isEmpty() && k == "uploads") k else "${SigV4.encode(k)}=${SigV4.encode(v)}" }
        return URI("${base.scheme}://$authority${fullPath.ifEmpty { "/" }}${if (text.isEmpty()) "" else "?$text"}")
    }

    private suspend fun send(method: String, uri: URI, body: ByteArray, headers: Map<String, String> = emptyMap()): HttpResponse<ByteArray> =
        http.sendAsync(request(method, uri, body, headers), HttpResponse.BodyHandlers.ofByteArray()).await()

    private suspend fun sendStreaming(method: String, uri: URI, headers: Map<String, String>): HttpResponse<InputStream> =
        http.sendAsync(request(method, uri, ByteArray(0), headers), HttpResponse.BodyHandlers.ofInputStream()).await()

    private fun request(method: String, uri: URI, body: ByteArray, headers: Map<String, String>): HttpRequest {
        val now = clock.now()
        val payloadHash = SigV4.sha256Hex(body)
        val signed = headers + mapOf("x-amz-date" to SigV4.amzDate(now), "x-amz-content-sha256" to payloadHash)
        val builder = HttpRequest.newBuilder(uri)
            .method(method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
        for ((name, value) in signed) builder.header(name, value)
        builder.header("Authorization", signer.authorization(method, uri, signed, payloadHash, now))
        return builder.build()
    }

    private fun HttpResponse<ByteArray>.expect(vararg statuses: Int): HttpResponse<ByteArray> {
        if (statusCode() !in statuses) throw S3Exception(statusCode(), String(body()))
        return this
    }

    private fun readUpTo(content: RawSource, max: Int): ByteArray {
        val buffer = Buffer()
        while (buffer.size < max) {
            if (content.readAtMostTo(buffer, max - buffer.size) == -1L) break
        }
        return buffer.readByteArray()
    }

    private fun httpDate(text: String): Instant =
        ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toKotlinInstant()

    private fun xml(bytes: ByteArray): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            isExpandEntityReferences = false
        }
        return factory.newDocumentBuilder().parse(bytes.inputStream())
    }

    private fun org.w3c.dom.Document.text(tag: String): String = documentElement.text(tag)

    private fun Element.text(tag: String): String =
        getElementsByTagName(tag).item(0)?.textContent ?: throw S3Exception(200, "S3's answer has no <$tag>.")

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

/** S3 answered with an error. */
public class S3Exception(
    /** The HTTP status. */
    public val status: Int,
    /** The error document S3 sent. */
    public val body: String,
) : RuntimeException("S3 answered $status: ${body.take(500)}")
