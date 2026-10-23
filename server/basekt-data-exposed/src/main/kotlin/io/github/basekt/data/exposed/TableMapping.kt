package io.github.basekt.data.exposed

import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema

/**
 * How [ExposedDataSource] lays a schema out as tables. Each resource becomes one table named [tablePrefix] plus the
 * resource's name, with:
 * - an `id` primary key, `VARCHAR(`[idLength]`)`;
 * - one nullable column per field: `TEXT` for strings, or `VARCHAR(n)` when a length rule caps them at `n`;
 *   `VARCHAR` for enums and references; `INTEGER`, `BIGINT`, `DECIMAL(`[decimalPrecision]`, `[decimalScale]`)`,
 *   `BOOLEAN`, and `TIMESTAMP WITH TIME ZONE` for instants;
 * - an index on `(reference, id)` for every reference field, which serves relation lookups;
 * - an index on `(fields..., id)` for each of [indexes], for the filters and sorts the application uses most.
 *   Ending with `id` lets the index serve keyset pagination too.
 *
 * Columns are nullable even for required fields: the service validates records, not the database. Reference fields
 * get no foreign-key constraint, for the same reason, so every adapter accepts the same writes.
 */
public data class TableMapping(
    /** Put before every table name, to share a database with other tables. Empty by default. */
    val tablePrefix: String = "",
    /** The length of id and reference columns. */
    val idLength: Int = 64,
    /** The total digits of decimal columns. */
    val decimalPrecision: Int = 38,
    /** The digits after the point of decimal columns. Values with more are rounded by the database. */
    val decimalScale: Int = 12,
    /** Extra indexes, each on some fields of one resource. */
    val indexes: List<IndexSpec> = emptyList(),
) {
    init {
        require(idLength >= 1) { "idLength must be at least 1, not $idLength." }
        require(decimalPrecision >= 1 && decimalScale in 0..decimalPrecision) {
            "Decimal columns need 1 <= precision and 0 <= scale <= precision, not ($decimalPrecision, $decimalScale)."
        }
    }

    /** Throws [IllegalArgumentException] if an index names a resource or field that [schema] doesn't have. */
    internal fun check(schema: Schema) {
        for (index in indexes) {
            val resource = requireNotNull(schema.resource(index.resource)) {
                "An index names the resource '${index.resource}', which the schema doesn't have."
            }
            for (field in index.fields) {
                require(field == Resource.ID || resource.field(field) != null) {
                    "An index on '${index.resource}' names the field '$field', which it doesn't have."
                }
            }
        }
    }
}

/** An index on [fields] of [resource], in order. [TableMapping] appends the id. */
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
