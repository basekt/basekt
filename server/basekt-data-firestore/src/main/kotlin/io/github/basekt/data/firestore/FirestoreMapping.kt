package io.github.basekt.data.firestore

import io.github.basekt.schema.Schema

/**
 * How [FirestoreDataSource] lays a schema out as collections. Each resource becomes one top-level collection, named
 * [collectionPrefix] plus the resource's name unless [collections] gives it another, whose documents have the record's
 * id as their document id and hold one value per field under the field's name, `null` when it isn't set: strings,
 * enums, references and file keys as strings, `int` and `long` as integers, booleans, instants as timestamps (which
 * keep microseconds, so finer digits are dropped), and decimals as [decimals] says.
 *
 * A collection that already exists, as it does when a schema describes documents another program wrote, is read as it
 * is: a number may be an integer or a double, a reference may be a document reference (read as its id), and a missing
 * field is `null`. What the other program stores that no field names is kept when a record is updated.
 */
public data class FirestoreMapping(
    /** Put before every collection name, to share a database with other collections. Empty by default. */
    val collectionPrefix: String = "",
    /** Collections that are not called after their resource: resource name to collection name. The prefix is not added. */
    val collections: Map<String, String> = emptyMap(),
    /** How decimal fields are stored. */
    val decimals: DecimalStorage = DecimalStorage.STRING,
    /** The collection that holds the counters of `sequence` fields, one document per counter. */
    val sequencesCollection: String = "basekt_sequences",
) {
    /** Throws [IllegalArgumentException] if the mapping names a resource the schema doesn't have, or a collection twice. */
    internal fun check(schema: Schema) {
        for (name in collections.keys) {
            requireNotNull(schema.resource(name)) { "The mapping names the resource '$name', which the schema doesn't have." }
        }
        val names = schema.resources.map { collectionOf(it.name) }
        for (name in names + sequencesCollection) {
            require(name.isNotEmpty() && '/' !in name && !(name.startsWith("__") && name.endsWith("__"))) {
                "'$name' can't be a Firestore collection id."
            }
        }
        require(names.toSet().size == names.size) { "Two resources share a collection: ${names.groupBy { it }.filter { it.value.size > 1 }.keys}." }
        require(sequencesCollection !in names) { "A resource's collection can't be called '$sequencesCollection': the sequences are kept there." }
    }

    internal fun collectionOf(resource: String): String = collections[resource] ?: (collectionPrefix + resource)
}

/** How [FirestoreDataSource] stores decimal fields. Either way, a decimal is read back from a string, an integer or a double. */
public enum class DecimalStorage {
    /**
     * As the decimal's text, which keeps every digit. Firestore compares strings by their characters, not by value, so
     * a filter or sort on a decimal field is checked in memory (see [FirestoreDataSource]).
     */
    STRING,

    /**
     * As a double, which Firestore compares and sorts by value, but which keeps only about 15 significant digits. Use
     * it for collections that already hold doubles.
     */
    DOUBLE,
}
