package io.github.basekt.storage.s3

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * A small S3 for tests: one path-style bucket in memory, with PUT, multipart uploads, HEAD, ranged GET, DELETE and
 * ListObjectsV2. It checks every request's Signature V4 by signing it again, so what the client signs is what it
 * sends.
 */
class FakeS3(private val bucket: String, private val accessKey: String, secretKey: String, region: String) : AutoCloseable {
    private class Stored(val bytes: ByteArray, val contentType: String, val etag: String, val lastModified: Instant)

    private val objects = ConcurrentSkipListMap<String, Stored>()
    private class Upload(val contentType: String, val parts: ConcurrentSkipListMap<Int, ByteArray> = ConcurrentSkipListMap())

    private val uploads = ConcurrentHashMap<String, Upload>()
    private val signer = SigV4(accessKey, secretKey, region)

    /** How many multipart uploads were completed, so a test can see one happened. */
    @Volatile var completedMultipart: Int = 0

    private val server: EmbeddedServer<*, *> = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
        routing { route("{...}") { handle { serve(call) } } }
    }.start(wait = false)

    /** The endpoint to give S3Settings. */
    val endpoint: String = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().first().port }}"

    override fun close() = server.stop(0, 0)

    private suspend fun serve(call: ApplicationCall) {
        val body = call.receive<ByteArray>()
        val uri = URI(endpoint + call.request.uri)
        if (!signed(call, uri, body)) {
            call.respondText("<Error><Code>SignatureDoesNotMatch</Code></Error>", ContentType.Application.Xml, HttpStatusCode.Forbidden)
            return
        }
        val segments = uri.rawPath.trim('/').split('/', limit = 2)
        if (segments[0] != bucket) return call.respondText("<Error><Code>NoSuchBucket</Code></Error>", ContentType.Application.Xml, HttpStatusCode.NotFound)
        val key = segments.getOrNull(1)?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
        val query = (uri.rawQuery ?: "").split('&').filter { it.isNotEmpty() }
            .associate { java.net.URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to java.net.URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8) }
        val method = call.request.httpMethod
        when {
            key == null && method == HttpMethod.Get -> list(call, query)
            key == null -> call.respond(HttpStatusCode.MethodNotAllowed)
            method == HttpMethod.Post && "uploads" in query -> {
                val id = UUID.randomUUID().toString()
                uploads[id] = Upload(call.request.headers[HttpHeaders.ContentType] ?: "application/octet-stream")
                call.respondText("<InitiateMultipartUploadResult><UploadId>$id</UploadId></InitiateMultipartUploadResult>", ContentType.Application.Xml)
            }
            method == HttpMethod.Put && "uploadId" in query -> {
                val upload = uploads[query["uploadId"]] ?: return call.respond(HttpStatusCode.NotFound)
                upload.parts[query.getValue("partNumber").toInt()] = body
                call.response.header(HttpHeaders.ETag, "\"${md5(body)}\"")
                call.respond(HttpStatusCode.OK)
            }
            method == HttpMethod.Post && "uploadId" in query -> {
                val upload = uploads.remove(query["uploadId"]) ?: return call.respond(HttpStatusCode.NotFound)
                val bytes = upload.parts.values.fold(ByteArray(0)) { all, part -> all + part }
                objects[key] = Stored(bytes, upload.contentType, "${md5(bytes)}-${upload.parts.size}", now())
                completedMultipart++
                call.respondText("<CompleteMultipartUploadResult><Key>$key</Key></CompleteMultipartUploadResult>", ContentType.Application.Xml)
            }
            method == HttpMethod.Delete && "uploadId" in query -> {
                uploads.remove(query["uploadId"])
                call.respond(HttpStatusCode.NoContent)
            }
            method == HttpMethod.Put -> {
                objects[key] = Stored(body, call.request.headers[HttpHeaders.ContentType] ?: "application/octet-stream", md5(body), now())
                call.response.header(HttpHeaders.ETag, "\"${md5(body)}\"")
                call.respond(HttpStatusCode.OK)
            }
            method == HttpMethod.Delete -> {
                objects.remove(key)
                call.respond(HttpStatusCode.NoContent)
            }
            method == HttpMethod.Head || method == HttpMethod.Get -> read(call, key, method == HttpMethod.Head)
            else -> call.respond(HttpStatusCode.MethodNotAllowed)
        }
    }

    private suspend fun read(call: ApplicationCall, key: String, headOnly: Boolean) {
        val stored = objects[key] ?: return call.respondText("<Error><Code>NoSuchKey</Code></Error>", ContentType.Application.Xml, HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.ETag, "\"${stored.etag}\"")
        call.response.header(HttpHeaders.LastModified, DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC).format(stored.lastModified.toJavaInstant()))
        val range = call.request.headers[HttpHeaders.Range]?.removePrefix("bytes=")?.split('-')?.let { it[0].toLong()..it[1].toLong() }
        if (headOnly) {
            call.response.header(HttpHeaders.ContentLength, stored.bytes.size.toString())
            call.respondBytes(ByteArray(0), ContentType.parse(stored.contentType), HttpStatusCode.OK)
            return
        }
        if (range == null) return call.respondBytes(stored.bytes, ContentType.parse(stored.contentType))
        call.response.header(HttpHeaders.ContentRange, "bytes ${range.first}-${range.last}/${stored.bytes.size}")
        call.respondBytes(stored.bytes.copyOfRange(range.first.toInt(), range.last.toInt() + 1), ContentType.parse(stored.contentType), HttpStatusCode.PartialContent)
    }

    private suspend fun list(call: ApplicationCall, query: Map<String, String>) {
        val prefix = query["prefix"].orEmpty()
        val after = query["start-after"]
        val max = query["max-keys"]?.toInt() ?: 1000
        val keys = objects.tailMap(maxOf(prefix, after ?: ""), after == null || after < prefix).entries
            .asSequence().filter { it.key.startsWith(prefix) }.take(max).toList()
        val xml = buildString {
            append("<ListBucketResult>")
            for ((key, stored) in keys) {
                append("<Contents><Key>$key</Key><Size>${stored.bytes.size}</Size><ETag>&quot;${stored.etag}&quot;</ETag>")
                append("<LastModified>${stored.lastModified}</LastModified></Contents>")
            }
            append("</ListBucketResult>")
        }
        call.respondText(xml, ContentType.Application.Xml)
    }

    private fun signed(call: ApplicationCall, uri: URI, body: ByteArray): Boolean {
        val authorization = call.request.headers[HttpHeaders.Authorization] ?: return false
        val signedHeaders = authorization.substringAfter("SignedHeaders=").substringBefore(',').split(';').filter { it != "host" }
        val payloadHash = call.request.headers["x-amz-content-sha256"] ?: return false
        if (payloadHash != SigV4.sha256Hex(body)) return false
        val date = call.request.headers["x-amz-date"] ?: return false
        val headers = signedHeaders.associateWith { call.request.headers[it] ?: return false }
        val now = Instant.parse(DateTimeFormatter.ISO_INSTANT.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).parse(date)))
        return authorization.startsWith("AWS4-HMAC-SHA256 Credential=$accessKey/") &&
            signer.authorization(call.request.httpMethod.value, uri, headers, payloadHash, now) == authorization
    }

    private fun now(): Instant = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)

    private fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}
