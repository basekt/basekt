package io.github.basekt.files

import io.github.basekt.server.BaseKt
import io.github.basekt.server.baseKt
import io.github.basekt.server.withIdentities
import io.github.basekt.storage.BlobKey
import io.github.basekt.storage.BlobStore
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.log
import io.ktor.server.application.pluginOrNull
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * The configuration of [BaseKtFiles]. Settings can come from the `basekt.files` section of the application's
 * configuration; values set in the `install` block win. The [store] and the buckets are set in code.
 */
public class BaseKtFilesConfig internal constructor(config: ApplicationConfig) {
    /** Where files are served. `basekt.files.path`, `/files` by default. */
    public var path: String = config.propertyOrNull("path")?.getString() ?: "/files"

    /** The default largest file a bucket takes, in bytes. `basekt.files.maxBytes`, 10 MiB by default. */
    public var maxBytes: Long = config.propertyOrNull("maxBytes")?.getString()?.toLong() ?: (10 * MEGABYTE)

    /** Where the bytes live. Required. */
    public var store: BlobStore? = null

    /**
     * How often to delete uploads that no file field points to, per each bucket's
     * [BucketBuilder.deleteUnattachedAfter]. `basekt.files.sweepEvery` (an ISO-8601 duration such as `PT1H`), an
     * hour by default; `null` never sweeps. Sweeping needs a store that is a [io.github.basekt.storage.BlobListing].
     */
    public var sweepEvery: Duration? = config.propertyOrNull("sweepEvery")?.getString()?.let(Duration::parseIsoString) ?: 1.hours

    internal val buckets = mutableListOf<BucketBuilder>()

    /** Adds a bucket named [name], with its limits and permissions. Nobody may do anything until [block] says so. */
    public fun bucket(name: String, block: BucketBuilder.() -> Unit) {
        require(BlobKey.parseOrNull(name)?.value == name && '/' !in name) {
            "'$name' isn't a bucket name: use letters, digits, '.', '_' and '-', starting with a letter or digit."
        }
        require(buckets.none { it.name == name }) { "The bucket '$name' is defined twice." }
        buckets += BucketBuilder(name, maxBytes).apply(block)
    }
}

/** What [BaseKtFiles] serves, for the application's own code and for other modules. Get it with [Application.baseKtFiles]. */
public class FilesRuntime internal constructor(
    /** Where the bytes live. */
    public val store: BlobStore,
    /** The buckets, by name. */
    public val buckets: Map<String, Bucket>,
    /** Where files are served, such as `/files`. */
    public val path: String,
) {
    /** The URL path a file is served at, such as `/files/images/3f1c…/lamp.jpg`. */
    public fun url(key: BlobKey): String = "$path/${key.value}"
}

/**
 * A stored file, as an upload answers it and as a `file` field refers to it.
 *
 * @property key where it is stored; its first segment is the bucket.
 * @property name the file name it was uploaded with, made safe.
 * @property size its length in bytes.
 * @property contentType its media type, worked out from its bytes.
 * @property url where to download it.
 */
@Serializable
public data class FileRef(
    val key: String,
    val bucket: String,
    val name: String,
    val size: Long,
    val contentType: String,
    val url: String,
)

private val FilesKey = AttributeKey<FilesRuntime>("BaseKtFiles")

internal val FileFieldsKey = AttributeKey<FileFields>("BaseKtFiles.fileFields")

/** The running [BaseKtFiles]: its store and buckets. */
public val Application.baseKtFiles: FilesRuntime get() = attributes[FilesKey]

/**
 * Serves files in buckets over HTTP, from any [BlobStore], under [BaseKtFilesConfig.path]:
 *
 * | Route | What it does |
 * |---|---|
 * | `POST /{bucket}` | uploads one file from a `multipart/form-data` body, and answers a [FileRef] |
 * | `GET`, `HEAD /{bucket}/…` | downloads a file, with byte ranges and ETags |
 * | `DELETE /{bucket}/…` | deletes a file |
 *
 * Uploads are streamed and stopped as soon as they pass the bucket's limit. Their type is worked out from their
 * bytes, never taken from the client when it could make a browser render the file. Downloads send `nosniff` and a
 * sandboxing CSP, and only images, audio, video, PDF and plain text are shown inline; everything else downloads as
 * an attachment.
 *
 * Callers are resolved by BaseKt's identity resolver, and each bucket's [Access][io.github.basekt.schema.Access]
 * rules decide what they may do. Errors are `ApiError` JSON bodies. Install [BaseKt] first.
 */
public val BaseKtFiles: ApplicationPlugin<BaseKtFilesConfig> = createApplicationPlugin(
    name = "BaseKtFiles",
    configurationPath = "basekt.files",
    createConfiguration = ::BaseKtFilesConfig,
) {
    checkNotNull(application.pluginOrNull(BaseKt)) { "Install BaseKt before BaseKtFiles." }
    val store = checkNotNull(pluginConfig.store) { "BaseKtFiles needs a store, such as LocalBlobStore(path)." }
    val path = "/" + pluginConfig.path.trim('/')
    require(path != "/") { "BaseKtFiles's path must not be '/'." }
    val buckets = pluginConfig.buckets.map { it.build() }.associateBy { it.name }
    require(buckets.isNotEmpty()) { "BaseKtFiles needs at least one bucket." }
    val runtime = FilesRuntime(store, buckets, path)
    application.attributes.put(FilesKey, runtime)

    val baseKt = application.baseKt
    val log = application.log
    val fileFields = FileFields(baseKt.schema, baseKt.dataSource, store, buckets) { message, error -> log.warn(message, error) }
    baseKt.service.extend(fileFields)
    application.attributes.put(FileFieldsKey, fileFields)

    val identities = baseKt.identities
    application.routing { withIdentities(identities) { fileRoutes(runtime, identities) } }

    val sweepEvery = pluginConfig.sweepEvery
    var sweeper: Job? = null
    on(MonitoringEvent(ApplicationStarted)) { app ->
        app.log.info("BaseKt files serves ${buckets.keys.joinToString()} under $path")
        if (sweepEvery != null) {
            sweeper = app.launch {
                while (isActive) {
                    delay(sweepEvery)
                    try {
                        val swept = fileFields.sweep()
                        if (swept > 0) app.log.info("BaseKt files deleted $swept unattached uploads")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        app.log.warn("BaseKt files couldn't sweep unattached uploads", e)
                    }
                }
            }
        }
    }
    on(MonitoringEvent(ApplicationStopping)) { sweeper?.cancel() }
}
