package io.github.basekt.files

import io.github.basekt.data.Comparison
import io.github.basekt.data.DataSource
import io.github.basekt.data.Filter
import io.github.basekt.data.Query
import io.github.basekt.schema.Field
import io.github.basekt.schema.FieldError
import io.github.basekt.schema.FieldErrorCode
import io.github.basekt.schema.FieldType
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import io.github.basekt.server.Change
import io.github.basekt.server.WriteExtension
import io.github.basekt.storage.BlobKey
import io.github.basekt.storage.BlobListing
import io.github.basekt.storage.BlobStore
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Keeps file fields and the files they point to in step:
 * - a write may only point a file field at a file that exists;
 * - after a commit, a file a record no longer points to is deleted, unless another record still does;
 * - [sweep] deletes uploads no record ever pointed to, once they're old enough.
 */
internal class FileFields(
    private val schema: Schema,
    private val dataSource: DataSource,
    private val store: BlobStore,
    private val buckets: Map<String, Bucket>,
    private val onError: (String, Throwable) -> Unit,
) : WriteExtension {
    /** Every file field, with its resource. */
    private val fields: List<Pair<Resource, Field>> =
        schema.resources.flatMap { resource -> resource.fields.filter { it.type == FieldType.FILE }.map { resource to it } }

    init {
        val missing = fields.map { it.second.bucket!! }.filterNot { it in buckets }.toSet()
        require(missing.isEmpty()) { "File fields use buckets BaseKtFiles doesn't define: ${missing.joinToString()}." }
    }

    override suspend fun check(resource: Resource, record: Record, transaction: DataSource): List<FieldError> =
        resource.fields.filter { it.type == FieldType.FILE }.mapNotNull { field ->
            val key = (record[field.name] as? Value.Text)?.value ?: return@mapNotNull null
            val blob = BlobKey.parseOrNull(key)
            if (blob != null && store.head(blob) != null) null
            else FieldError(field.name, FieldErrorCode.MISSING_REFERENCE, "There is no file '$key' in '${field.bucket}'. Upload it first.")
        }

    override suspend fun committed(change: Change) {
        val dropped = when (change) {
            is Change.Created -> emptyList()
            is Change.Updated -> fileKeys(change.resource, change.before) - fileKeys(change.resource, change.after).toSet()
            is Change.Deleted -> fileKeys(change.resource, change.before)
        }
        for (key in dropped) {
            try {
                if (!referenced(key)) store.delete(key)
            } catch (e: Exception) {
                onError("Couldn't delete the file '$key', which no record points to any more", e)
            }
        }
    }

    /**
     * Deletes the files in buckets that file fields use, older than the bucket's [Bucket.deleteUnattachedAfter], that
     * no record points to. Returns how many it deleted. Needs a store that is a [BlobListing]; otherwise it does
     * nothing.
     */
    suspend fun sweep(now: Instant = Clock.System.now()): Int {
        val listing = store as? BlobListing ?: return 0
        var deleted = 0
        for (bucket in fields.mapNotNull { buckets[it.second.bucket] }.distinct()) {
            val after = bucket.deleteUnattachedAfter ?: continue
            var cursor: BlobKey? = null
            while (true) {
                val page = listing.list("${bucket.name}/", cursor, PAGE)
                for (blob in page) {
                    if (now - blob.lastModified < after) continue
                    try {
                        if (!referenced(blob.key) && store.delete(blob.key)) deleted++
                    } catch (e: Exception) {
                        onError("Couldn't sweep the unattached file '${blob.key}'", e)
                    }
                }
                if (page.size < PAGE) break
                cursor = page.last().key
            }
        }
        return deleted
    }

    private fun fileKeys(resource: Resource, record: Record): List<BlobKey> =
        resource.fields.filter { it.type == FieldType.FILE }
            .mapNotNull { (record[it.name] as? Value.Text)?.value?.let(BlobKey::parseOrNull) }

    /** Whether any record's file field in the key's bucket points to [key]. */
    private suspend fun referenced(key: BlobKey): Boolean = fields
        .filter { (_, field) -> field.bucket == key.first }
        .any { (resource, field) ->
            dataSource.list(resource, Query(filter = Filter.Compare(field.name, Comparison.EQ, Value.Text(key.value)), limit = 1)).items.isNotEmpty()
        }

    private companion object {
        const val PAGE = 500
    }
}
