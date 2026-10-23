package io.github.basekt.files

import io.github.basekt.schema.Access
import io.github.basekt.server.ApiError
import io.github.basekt.server.ApiException
import io.github.basekt.server.ErrorCode
import io.github.basekt.server.Identity
import io.github.basekt.server.IdentityResolver
import io.github.basekt.server.answeringErrors
import io.github.basekt.server.respondError
import io.github.basekt.storage.BlobContent
import io.github.basekt.storage.BlobInfo
import io.github.basekt.storage.BlobKey
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.defaultForFilePath
import io.ktor.http.fromHttpToGmtDate
import io.ktor.http.toHttpDate
import io.ktor.server.request.contentLength
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.isMultipart
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.asSource
import kotlinx.io.readByteArray
import kotlinx.io.write
import kotlinx.serialization.json.Json
import java.util.UUID

private val FilesJson = Json { encodeDefaults = true }

internal fun Route.fileRoutes(files: FilesRuntime, identities: IdentityResolver) {
    route(files.path) {
        post("{bucket}") {
            handle(identities) { caller ->
                val bucket = bucket(files)
                allow(bucket.upload, caller, "upload to '${bucket.name}'")
                val ref = upload(files, bucket)
                call.respondText(FilesJson.encodeToString(FileRef.serializer(), ref), ContentType.Application.Json, HttpStatusCode.Created)
            }
        }
        get("{key...}") { handle(identities) { caller -> download(files, caller) } }
        head("{key...}") { handle(identities) { caller -> download(files, caller) } }
        delete("{key...}") {
            handle(identities) { caller ->
                val (bucket, key) = target(files)
                allow(bucket.delete, caller, "delete from '${bucket.name}'")
                if (!files.store.delete(key)) throw ApiException(ErrorCode.NOT_FOUND, "There is no file '$key'.")
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

/** Streams the one file part of a multipart body into the bucket, sniffing its type and enforcing its limit. */
private suspend fun RoutingContext.upload(files: FilesRuntime, bucket: Bucket): FileRef {
    if (!call.request.isMultipart()) {
        throw ApiException(ErrorCode.BAD_REQUEST, "Upload a file as multipart/form-data, with the file in a part named 'file'.")
    }
    // A body far bigger than the limit is refused before reading it; multipart framing adds a little.
    val declared = call.request.contentLength()
    if (declared != null && declared > bucket.maxBytes + MULTIPART_SLACK) tooLarge(bucket)

    var ref: FileRef? = null
    // A failure, such as passing the limit, stops reading the body and answers at once.
    call.receiveMultipart().forEachPart { part ->
        try {
            if (part is PartData.FileItem && ref == null) {
                ref = store(files, bucket, part.originalFileName, part.contentType?.toString(), part.provider())
            }
        } finally {
            part.dispose()
        }
    }
    return ref ?: throw ApiException(ErrorCode.BAD_REQUEST, "The upload has no file part.")
}

private suspend fun store(
    files: FilesRuntime,
    bucket: Bucket,
    fileName: String?,
    declaredType: String?,
    body: ByteReadChannel,
): FileRef = withContext(Dispatchers.IO) {
    val input = body.toInputStream()
    val head = input.readNBytes(ContentSniffer.PREFIX)
    val type = ContentSniffer.sniff(head, declaredType ?: fileName?.let { ContentType.defaultForFilePath(it).toString() })
    if (!bucket.accepts(type)) {
        throw ApiException(ErrorCode.UNSUPPORTED_TYPE, "'${bucket.name}' doesn't take $type files.")
    }
    val name = safeName(fileName)
    val key = BlobKey.of("${bucket.name}/${UUID.randomUUID().toString().replace("-", "")}/$name")
    val content = Limited(Buffer().apply { write(head) }, input.asSource(), bucket)
    val info = files.store.put(key, content, type)
    FileRef(key.value, bucket.name, name, info.size, info.contentType, files.url(key))
}

/** Gives [head] then [rest], and fails with a 413 once more than the bucket's limit has gone through. */
private class Limited(private val head: Buffer, private val rest: RawSource, private val bucket: Bucket) : RawSource {
    private var total = 0L

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        val read = if (!head.exhausted()) head.readAtMostTo(sink, byteCount) else rest.readAtMostTo(sink, byteCount)
        if (read > 0) {
            total += read
            if (total > bucket.maxBytes) tooLarge(bucket)
        }
        return read
    }

    override fun close() = rest.close()
}

private fun tooLarge(bucket: Bucket): Nothing =
    throw ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "'${bucket.name}' takes files of at most ${bucket.maxBytes} bytes.")

/** The upload's file name reduced to a safe key segment, such as `Desk lamp (1).JPG` → `Desk-lamp-1-.JPG`. */
internal fun safeName(fileName: String?): String {
    val base = fileName.orEmpty().substringAfterLast('/').substringAfterLast('\\')
    val cleaned = base.replace(Regex("[^A-Za-z0-9._-]+"), "-").trimStart('.', '-', '_').take(100)
    return cleaned.ifEmpty { "file" }
}

private suspend fun RoutingContext.download(files: FilesRuntime, caller: Identity?) {
    val (bucket, key) = target(files)
    allow(bucket.read, caller, "read '${bucket.name}'")
    val info = files.store.head(key) ?: throw ApiException(ErrorCode.NOT_FOUND, "There is no file '$key'.")
    val etag = "\"${info.etag}\""

    val headers = call.response.headers
    headers.append(HttpHeaders.ETag, etag)
    headers.append(HttpHeaders.LastModified, GMTDate(info.lastModified.toEpochMilliseconds()).toHttpDate())
    headers.append(HttpHeaders.AcceptRanges, "bytes")
    headers.append("X-Content-Type-Options", "nosniff")
    headers.append("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
    headers.append(HttpHeaders.CacheControl, if (bucket.read == Access.Everyone) "public, max-age=3600" else "private, max-age=3600")
    headers.append(HttpHeaders.ContentDisposition, disposition(info))

    if (notModified(etag, info)) {
        call.respond(HttpStatusCode.NotModified)
        return
    }
    val rangeHeader = call.request.header(HttpHeaders.Range)?.takeIf { ifRangeMatches(etag) }
    val range = rangeHeader?.let { parseRange(it, info.size) }
    if (rangeHeader != null && range == null) {
        call.response.header(HttpHeaders.ContentRange, "bytes */${info.size}")
        val error = ApiError(ErrorCode.INVALID_QUERY, "The range '$rangeHeader' isn't within the file's ${info.size} bytes.")
        call.respondError(error, HttpStatusCode.RequestedRangeNotSatisfiable)
        return
    }
    if (call.request.httpMethod == HttpMethod.Head) {
        call.respond(BlobBody(info, range, null))
        return
    }
    val content = files.store.open(key, range) ?: throw ApiException(ErrorCode.NOT_FOUND, "There is no file '$key'.")
    call.respond(BlobBody(content.info, range, content))
}

/** The file's bytes, or just its headers for a HEAD, with a 206 and `Content-Range` for a range. */
private class BlobBody(
    private val info: BlobInfo,
    private val range: LongRange?,
    private val content: BlobContent?,
) : OutgoingContent.WriteChannelContent() {
    override val contentType: ContentType = ContentType.parse(info.contentType)
    override val contentLength: Long = range?.let { it.last - it.first + 1 } ?: info.size
    override val status: HttpStatusCode = if (range != null) HttpStatusCode.PartialContent else HttpStatusCode.OK
    override val headers = io.ktor.http.Headers.build {
        if (range != null) append(HttpHeaders.ContentRange, "bytes ${range.first}-${range.last}/${info.size}")
    }

    override suspend fun writeTo(channel: ByteWriteChannel) {
        val content = content ?: return
        content.use {
            while (true) {
                val chunk = withContext(Dispatchers.IO) {
                    val buffer = Buffer()
                    if (it.source.readAtMostTo(buffer, CHUNK) == -1L) null else buffer.readByteArray()
                } ?: break
                channel.writeFully(chunk)
            }
        }
    }
}

/** Inline for types a browser shows without running anything; an attachment for the rest. */
private fun disposition(info: BlobInfo): String {
    val type = info.contentType
    val inline = !ContentSniffer.isDangerous(type) &&
        (type.startsWith("image/") || type.startsWith("audio/") || type.startsWith("video/") || type == "application/pdf" || type == "text/plain")
    val base = if (inline) ContentDisposition.Inline else ContentDisposition.Attachment
    return base.withParameter(ContentDisposition.Parameters.FileName, info.key.name).toString()
}

private fun RoutingContext.notModified(etag: String, info: BlobInfo): Boolean {
    val ifNoneMatch = call.request.header(HttpHeaders.IfNoneMatch)
    if (ifNoneMatch != null) return ifNoneMatch.split(',').any { it.trim() == etag || it.trim() == "*" }
    val since = call.request.header(HttpHeaders.IfModifiedSince)?.let { runCatching { it.fromHttpToGmtDate() }.getOrNull() } ?: return false
    return info.lastModified.toEpochMilliseconds() / 1000 <= since.timestamp / 1000
}

/** A range applies unless `If-Range` names another version. */
private fun RoutingContext.ifRangeMatches(etag: String): Boolean =
    call.request.header(HttpHeaders.IfRange)?.let { it.trim() == etag } ?: true

/** One `bytes=` range within [size], or `null` if it can't be served. Several ranges are served as the whole file. */
internal fun parseRange(header: String, size: Long): LongRange? {
    val spec = header.trim().removePrefix("bytes=").takeIf { header.trim().startsWith("bytes=") } ?: return null
    if (',' in spec) return 0L..(size - 1).coerceAtLeast(0)
    val (start, end) = spec.split('-', limit = 2).map { it.trim() }.takeIf { it.size == 2 } ?: return null
    val range = when {
        start.isEmpty() -> end.toLongOrNull()?.takeIf { it > 0 }?.let { maxOf(0, size - it)..(size - 1) }
        else -> start.toLongOrNull()?.let { first -> first..(end.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)) }
    } ?: return null
    return range.takeIf { size > 0 && range.first in 0..range.last && range.last < size }
}

private fun RoutingContext.bucket(files: FilesRuntime): Bucket {
    val name = call.parameters["bucket"]!!
    return files.buckets[name] ?: throw ApiException(ErrorCode.NOT_FOUND, "There is no bucket '$name'.")
}

/** The bucket and key a download or delete names. Anything that isn't a key in a known bucket is a 404. */
private fun RoutingContext.target(files: FilesRuntime): Pair<Bucket, BlobKey> {
    val text = call.parameters.getAll("key").orEmpty().joinToString("/")
    val key = BlobKey.parseOrNull(text)?.takeIf { '/' in it.value }
        ?: throw ApiException(ErrorCode.NOT_FOUND, "There is no file '$text'.")
    val bucket = files.buckets[key.first] ?: throw ApiException(ErrorCode.NOT_FOUND, "There is no bucket '${key.first}'.")
    return bucket to key
}

private fun allow(access: Access, caller: Identity?, what: String) {
    if (access.allows(caller?.roles)) return
    if (caller == null) throw ApiException(ErrorCode.UNAUTHORIZED, "Sign in to $what.")
    throw ApiException(ErrorCode.FORBIDDEN, "Your roles don't allow you to $what.")
}

/** Resolves the caller, runs [block], and answers any [ApiException] with its JSON body. */
private suspend fun RoutingContext.handle(identities: IdentityResolver, block: suspend RoutingContext.(Identity?) -> Unit) {
    call.answeringErrors { block(identities.resolve(call)) }
}

private const val CHUNK = 64L * 1024
private const val MULTIPART_SLACK = 64L * 1024
