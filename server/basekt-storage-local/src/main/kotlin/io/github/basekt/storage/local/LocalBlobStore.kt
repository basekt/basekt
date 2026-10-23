package io.github.basekt.storage.local

import io.github.basekt.storage.BlobContent
import io.github.basekt.storage.BlobInfo
import io.github.basekt.storage.BlobKey
import io.github.basekt.storage.BlobListing
import io.github.basekt.storage.BlobStore
import io.github.basekt.storage.requireRangeWithin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.asSource
import kotlinx.io.readByteArray
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Properties
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A [BlobStore] on the local filesystem, under [root]. It also implements [BlobListing].
 *
 * A blob is a plain file at `root/<key>`, so other tools can read it. Its content type and etag sit beside it in
 * `root/.basekt-meta/<key>.properties`, and writes go through `root/.basekt-tmp` and are moved into place, so a
 * failed put leaves the old blob. Keys can't name the `.basekt-` directories: [BlobKey] segments never start with
 * a dot.
 *
 * Two limits come from the filesystem: a key can't be both a blob and a prefix of another (`a` and `a/b`), and on
 * Windows a put may fail while another call is reading the same blob.
 */
public class LocalBlobStore(
    root: Path,
    private val clock: Clock = Clock.System,
) : BlobStore, BlobListing {
    private val root: Path = root.toAbsolutePath().normalize()
    private val metaRoot = this.root.resolve(".basekt-meta")
    private val tmp = this.root.resolve(".basekt-tmp")

    init {
        Files.createDirectories(this.root)
    }

    override suspend fun put(key: BlobKey, content: RawSource, contentType: String): BlobInfo = withContext(Dispatchers.IO) {
        val target = path(key)
        Files.createDirectories(tmp)
        val blobTmp = Files.createTempFile(tmp, "blob", ".part")
        val metaTmp = Files.createTempFile(tmp, "meta", ".part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            Files.newOutputStream(blobTmp).use { out ->
                val buffer = Buffer()
                while (content.readAtMostTo(buffer, CHUNK) != -1L) {
                    val bytes = buffer.readByteArray()
                    digest.update(bytes)
                    out.write(bytes)
                    size += bytes.size
                }
            }
            val info = BlobInfo(key, size, contentType, digest.digest().hex().take(32), clock.now())
            Files.newOutputStream(metaTmp).use { out -> info.toProperties().store(out, null) }
            Files.createDirectories(target.parent)
            Files.createDirectories(metaPath(key).parent)
            moveIntoPlace(metaTmp, metaPath(key))
            moveIntoPlace(blobTmp, target)
            info
        } finally {
            blobTmp.deleteIfExists()
            metaTmp.deleteIfExists()
        }
    }

    override suspend fun head(key: BlobKey): BlobInfo? = withContext(Dispatchers.IO) {
        val file = path(key)
        if (!file.isRegularFile()) return@withContext null
        try {
            info(key, Files.size(file))
        } catch (_: NoSuchFileException) {
            null
        }
    }

    override suspend fun open(key: BlobKey, range: LongRange?): BlobContent? = withContext(Dispatchers.IO) {
        val channel = try {
            FileChannel.open(path(key), StandardOpenOption.READ)
        } catch (_: NoSuchFileException) {
            return@withContext null
        } catch (_: IOException) {
            // A directory, such as the prefix of another key, isn't a blob.
            return@withContext null
        }
        try {
            val info = info(key, channel.size())
            requireRangeWithin(range, info.size)
            if (range != null) channel.position(range.first)
            val length = range?.let { it.last - it.first + 1 } ?: info.size
            BlobContent(info, range, Limited(Channels.newInputStream(channel).asSource(), length))
        } catch (e: Throwable) {
            channel.close()
            throw e
        }
    }

    override suspend fun delete(key: BlobKey): Boolean = withContext(Dispatchers.IO) {
        val file = path(key)
        val deleted = file.isRegularFile() && file.deleteIfExists()
        metaPath(key).deleteIfExists()
        deleted
    }

    override suspend fun list(prefix: String, after: BlobKey?, limit: Int): List<BlobInfo> = withContext(Dispatchers.IO) {
        if (!root.exists()) return@withContext emptyList()
        val keys = Files.walk(root).use { paths ->
            paths.filter { it.isRegularFile() }
                .map { root.relativize(it).invariantSeparatorsPathString }
                .filter { !it.startsWith(".basekt-") && it.startsWith(prefix) && (after == null || it > after.value) }
                .sorted()
                .toList()
        }
        keys.asSequence()
            .mapNotNull { BlobKey.parseOrNull(it) }
            .mapNotNull { key -> runCatching { info(key, Files.size(path(key))) }.getOrNull() }
            .take(limit)
            .toList()
    }

    /** The file of [key], checked to be inside [root] even though [BlobKey] already rules out escapes. */
    private fun path(key: BlobKey): Path {
        val path = root.resolve(key.value).normalize()
        check(path.startsWith(root) && path != root) { "The key '$key' resolves outside the store." }
        return path
    }

    private fun metaPath(key: BlobKey): Path = metaRoot.resolve("${key.value}.properties").normalize()

    /** The blob's info: its size as given, and the content type and etag from its metadata file. */
    private fun info(key: BlobKey, size: Long): BlobInfo {
        val properties = Properties()
        try {
            Files.newInputStream(metaPath(key)).use(properties::load)
        } catch (_: NoSuchFileException) {
            // A file put there by something else: describe it from the file itself.
            val modified = Files.getLastModifiedTime(path(key)).toInstant()
            return BlobInfo(key, size, "application/octet-stream", "$size-${modified.toEpochMilli()}", Instant.fromEpochMilliseconds(modified.toEpochMilli()))
        }
        return BlobInfo(
            key = key,
            size = size,
            contentType = properties.getProperty("contentType", "application/octet-stream"),
            etag = properties.getProperty("etag", size.toString()),
            lastModified = Instant.parse(properties.getProperty("lastModified")),
        )
    }

    private fun BlobInfo.toProperties() = Properties().apply {
        setProperty("contentType", contentType)
        setProperty("etag", etag)
        setProperty("lastModified", lastModified.toString())
    }

    private fun moveIntoPlace(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    /** Gives at most [remaining] bytes of [source]. */
    private class Limited(private val source: RawSource, private var remaining: Long) : RawSource {
        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            if (remaining == 0L) return -1L
            val read = source.readAtMostTo(sink, minOf(byteCount, remaining))
            if (read > 0) remaining -= read
            return read
        }

        override fun close() = source.close()
    }

    private companion object {
        const val CHUNK = 64L * 1024
    }
}
