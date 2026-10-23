package io.github.basekt.admin

import io.github.basekt.schema.Auto
import io.github.basekt.schema.FieldType
import io.github.basekt.schema.Operation
import io.github.basekt.schema.Rule
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/*
 * The admin API's wire format. Admin panels compile against TypeScript types generated from these classes
 * (typescript/basekt-admin-api.ts), so changes here are contract changes: see AdminSchema.
 */

/**
 * What the current caller may see and do, for an admin panel to render itself from: `GET {path}/schema`.
 *
 * **The contract is additive only.** Within [version] 1, properties and enum values may be added, and panels must
 * ignore ones they don't know. Nothing is removed, renamed or given another type or meaning; such a change needs a
 * new [version].
 */
@Serializable
public data class AdminSchema(
    /** The version of this contract. Always [ADMIN_SCHEMA_VERSION] from this library. */
    val version: Int = ADMIN_SCHEMA_VERSION,
    /** Who is asking, or `null` for an anonymous caller. */
    val caller: AdminCaller?,
    /** The resources the caller may perform at least one operation on, in schema order. */
    val resources: List<AdminResource>,
)

/** The version of the [AdminSchema] contract this library serves. */
public const val ADMIN_SCHEMA_VERSION: Int = 1

/** The caller of an admin request. */
@Serializable
public data class AdminCaller(
    /** The caller's id. */
    val id: String,
    /** The caller's roles, sorted. */
    val roles: List<String>,
)

/** A resource as an admin panel sees it. */
@Serializable
public data class AdminResource(
    /** The resource's name, used in admin routes. */
    val name: String,
    /** The fields, in declaration order, without the id. */
    val fields: List<AdminField>,
    /** The relations whose target the caller may read. */
    val relations: List<AdminRelation>,
    /** The operations the caller may perform. */
    val operations: List<Operation>,
    /** The fields that the list's `search` parameter looks in. */
    val searchable: List<String>,
    /** The fields a list can sort by, including the id. */
    val sortable: List<String>,
)

/** A field as an admin panel sees it. */
@Serializable
public data class AdminField(
    /** The field's name. */
    val name: String,
    /** The field's type. Decimals travel as strings and instants as ISO-8601 strings. */
    val type: FieldType,
    /** Whether a record must have a value. */
    val required: Boolean,
    /** The allowed values of an enum field, or `null`. */
    val values: List<String>?,
    /** The resource a reference field points to, or `null`. */
    val target: String?,
    /** The checks on the field's value, for the panel to check forms with too. */
    val rules: List<Rule>,
    /** The bucket a file field's files are uploaded to, or `null`. */
    val bucket: String? = null,
    /**
     * The values the caller may give the field when they create a record, when a scope limits them, or `null`. A
     * panel can prefill the only one or offer a choice; a create that leaves out a field with one value gets it.
     */
    val scopedValues: List<String>? = null,
    /** What the server sets the field to, or `null` for a field the caller writes. A panel leaves it out of forms. */
    val auto: Auto? = null,
)

/** A relation as an admin panel sees it. */
@Serializable
public data class AdminRelation(
    /** The relation's name. */
    val name: String,
    /** Which side holds the reference. */
    val kind: RelationKind,
    /** The resource on the other side. */
    val target: String,
    /** The reference field: on this resource for [RelationKind.BELONGS_TO], on [target] for [RelationKind.HAS_MANY]. */
    val field: String,
)

/** Which side of an [AdminRelation] holds the reference. */
@Serializable
public enum class RelationKind {
    /** This resource's record holds the target's id. */
    @SerialName("belongsTo") BELONGS_TO,

    /** The target's records hold this record's id. */
    @SerialName("hasMany") HAS_MANY,
}

/** One page of a list: `GET {path}/resources/{name}/records`. */
@Serializable
public data class AdminPage(
    /** The records, as JSON objects keyed by field name, with the id first. */
    val items: List<JsonObject>,
    /** The cursor of the next page, or `null` on the last one. */
    val nextCursor: String?,
    /** Whether there is a next page. */
    val hasNext: Boolean,
    /** How many records match, ignoring the cursor, or `null` if the data source can't count. */
    val total: Long?,
)

/** The body of `POST {path}/resources/{name}/bulk-delete`. */
@Serializable
public data class BulkDeleteRequest(
    /** The ids to delete. */
    val ids: List<String>,
)

/** What a bulk delete did. Each record is deleted in its own transaction. */
@Serializable
public data class BulkDeleteResult(
    /** The ids that were deleted. */
    val deleted: List<String>,
    /** The ids that weren't, with why. */
    val failed: List<BulkFailure>,
)

/** A record a bulk operation couldn't handle. */
@Serializable
public data class BulkFailure(
    /** The record's id. */
    val id: String,
    /** Why. */
    val error: io.github.basekt.server.ApiError,
)

/** The JSON format of the admin API, matching the schema's: a `kind` property tells sealed variants apart. */
internal val AdminJson: Json = Json {
    classDiscriminator = "kind"
    encodeDefaults = true
}
