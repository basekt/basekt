package io.github.basekt.data.mongo

import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema

/**
 * How [MongoDataSource] lays a schema out as collections. Each resource becomes one collection, named [collectionPrefix]
 * plus the resource's name unless [collections] gives it another, whose documents hold:
 * - `_id`, the record's id;
 * - one value per field under the field's name, `null` when it isn't set: strings, enums, references and file keys as
 *   strings, `int` as an int32, `long` as an int64, decimals as Decimal128, booleans, and instants as dates. A date
 *   has millisecond precision, so an instant's finer digits are dropped.
 *
 * A collection that already exists, as it does when a schema describes documents another program wrote, is read as it
 * is: a number may be any numeric type, an id or a reference may be an ObjectId or a string, and a missing field is
 * `null`. What the other program stores that no field names is never touched.
 *
 * **ObjectIds.** BaseKt ids and references are strings. A field named in [objectIds] is stored as an ObjectId when its
 * value is 24 hexadecimal digits, and as a string otherwise, and is read back as the hex string. Name [Resource.ID] to
 * keep `_id` an ObjectId. New ids are ObjectIds' hex strings, so they fit such a field.
 */
public data class MongoMapping(
    /** Put before every collection name, to share a database with other collections. Empty by default. */
    val collectionPrefix: String = "",
    /** Collections that are not called after their resource: resource name to collection name. The prefix is not added. */
    val collections: Map<String, String> = emptyMap(),
    /** Per resource, the fields stored as ObjectIds. Name [Resource.ID] for `_id`. */
    val objectIds: Map<String, Set<String>> = emptyMap(),
    /** Extra indexes, each on some fields of one resource. */
    val indexes: List<IndexSpec> = emptyList(),
    /** The collection that holds the counters of `sequence` fields. */
    val sequencesCollection: String = "basekt_sequences",
) {
    /** Throws [IllegalArgumentException] if the mapping names a resource or field that [schema] doesn't have. */
    internal fun check(schema: Schema) {
        for (name in collections.keys + objectIds.keys + indexes.map { it.resource }) {
            requireNotNull(schema.resource(name)) { "The mapping names the resource '$name', which the schema doesn't have." }
        }
        for ((name, fields) in objectIds) {
            val resource = schema.resource(name)!!
            for (field in fields) {
                require(field == Resource.ID || resource.field(field) != null) {
                    "The mapping stores '$name.$field' as an ObjectId, but '$name' has no such field."
                }
            }
        }
        for (index in indexes) {
            val resource = schema.resource(index.resource)!!
            for (field in index.fields) {
                require(field == Resource.ID || resource.field(field) != null) {
                    "An index on '${index.resource}' names the field '$field', which it doesn't have."
                }
            }
        }
        val names = schema.resources.map { collectionOf(it.name) }
        require(names.toSet().size == names.size) { "Two resources share a collection: ${names.groupBy { it }.filter { it.value.size > 1 }.keys}." }
        require(sequencesCollection !in names) { "A resource's collection can't be called '$sequencesCollection': the sequences are kept there." }
    }

    internal fun collectionOf(resource: String): String = collections[resource] ?: (collectionPrefix + resource)
}

/** An index on [fields] of [resource], in order. [MongoDataSource] appends the id. */
public data class IndexSpec(
    /** The resource's name. */
    val resource: String,
    /** The fields, most significant first. */
    val fields: List<String>,
) {
    init {
        require(fields.isNotEmpty()) { "An index on '$resource' needs at least one field." }
    }

    /** Creates an index on [fields] of [resource]. */
    public constructor(resource: String, vararg fields: String) : this(resource, fields.toList())
}

/** What [MongoDataSource.migrate] changed in the database, and what it found but left alone. */
public data class MigrationReport(
    /** The indexes it created, one sentence each. Empty when the database already matched the schema. */
    val applied: List<String>,
    /** What it found and left alone, one sentence each: an index that differs from the one wanted, or data of another type. */
    val notes: List<String>,
)
