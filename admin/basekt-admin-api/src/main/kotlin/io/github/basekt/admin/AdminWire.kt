package io.github.basekt.admin

import io.github.basekt.schema.ActionTarget
import io.github.basekt.schema.Auto
import io.github.basekt.schema.Chart
import io.github.basekt.schema.FieldType
import io.github.basekt.schema.IdSource
import io.github.basekt.schema.MarkdownOptions
import io.github.basekt.schema.Operation
import io.github.basekt.schema.ReportCompare
import io.github.basekt.schema.ReportFilter
import io.github.basekt.schema.ReportFunction
import io.github.basekt.schema.ReportOrder
import io.github.basekt.schema.ReportRange
import io.github.basekt.schema.Rule
import io.github.basekt.schema.TimeBucket
import io.github.basekt.server.FilterOperator
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
    /** The actions the caller may run. */
    val actions: List<AdminAction> = emptyList(),
    /** The menu heading to list the resource under, or `null` for the top of the menu. */
    val group: String? = null,
    /** The charts of its records, when the caller may read them. Their numbers come from the aggregate route. */
    val reports: List<AdminReport> = emptyList(),
    /** Links from a record to the host app's own pages. A panel fills `{id}` and `{field}` from the record. */
    val links: List<AdminLink> = emptyList(),
    /** Where record ids come from: a panel asks for one on create when the client chooses it. */
    val id: AdminId = AdminId(),
    /**
     * Whether the records come from the application's own code rather than the data source, so no one may create,
     * update or delete them, whatever their roles. A panel shows no edit controls; actions still run.
     */
    val readOnly: Boolean = false,
    /**
     * The filters a list accepts, by field, when they are fewer than every operator that suits each field, as a
     * provided resource's are; `null` when every field takes every operator its type allows.
     */
    val filters: List<AdminFilter>? = null,
    /** Optional presentation hints for the panel: card slots, inline detail layout and row ordering. */
    val view: AdminView? = null,
)

/** A field a list may be filtered on, and the operators it takes. See [AdminResource.filters]. */
@Serializable
public data class AdminFilter(
    /** The field's name, or `id`. */
    val field: String,
    /** The operators it takes, as in `{field}[{operator}]={value}`. */
    val operators: List<FilterOperator>,
)

/**
 * How a resource's record ids are made, as an admin panel sees it. With [IdSource.NATURAL] a create form asks for the
 * id, checked by [rules]; with [IdSource.KEY] the server makes it from [key], whose fields can't change once created.
 */
@Serializable
public data class AdminId(
    /** Who chooses a new record's id. */
    val source: IdSource = IdSource.GENERATED,
    /** The checks on an id the client chooses. */
    val rules: List<Rule> = emptyList(),
    /** For [IdSource.KEY]: the fields whose values make the id, in order. */
    val key: List<String> = emptyList(),
)

/**
 * A chart as an admin panel sees it. A panel draws it from `GET {path}/resources/{name}/aggregate` with `groupBy`
 * (`groupBy={field}:{bucket}` and `timeZone` for a time series), `{function}={field}` (or `count`), `sort`, `limit`
 * and its [filters] as `{field}[{operator}]={values}`, and plots each group's [measure]. A time series in group order
 * shows its latest [limit] buckets. A [range] is turned into `{field}[gte]` and `{field}[lt]` dates when it is drawn.
 */
@Serializable
public data class AdminReport(
    /** The report's name, unique on its resource. */
    val name: String,
    /** How to draw it. */
    val chart: Chart,
    /** The field whose values make the groups, or `null` for one number. */
    val groupBy: String?,
    /** What to compute for each group. */
    val function: ReportFunction,
    /** The field to compute it over, or `null` to count records. */
    val field: String?,
    /** The name of the computed value in each group of the aggregate, such as `count` or `sum_total`. */
    val measure: String,
    /** The order of the groups. */
    val order: ReportOrder,
    /** The most groups to show. */
    val limit: Int,
    /** The span of time the instant [groupBy] field is grouped into, or `null` to group by its values. */
    val bucket: TimeBucket? = null,
    /** The time zone region whose calendar [bucket] and [range] follow, or `null` for UTC. */
    val timeZone: String? = null,
    /** The conditions a record must meet to count. */
    val filters: List<ReportFilter> = emptyList(),
    /** The rolling window of time a record must fall in, or `null` for all time. */
    val range: ReportRange? = null,
    /** The earlier window to show beside [range]'s by default, or `null`. A panel runs the aggregate again for it. */
    val compare: ReportCompare? = null,
)

/** An action as an admin panel sees it: a button, and a form for its input. */
@Serializable
public data class AdminAction(
    /** The action's name, used in admin routes. */
    val name: String,
    /** Whether it runs on one record or on the resource as a whole. */
    val target: ActionTarget,
    /** The fields of its input form. */
    val input: List<AdminField>,
    /** A question to ask before running it, or `null`. */
    val confirm: String?,
    /** For a workflow's transition, the field that holds the record's state; `null` for other actions. */
    val stateField: String? = null,
    /** For a workflow's transition, the states it may start from; a panel offers it only on records in one of them. */
    val from: List<String>? = null,
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
    /** Whether the field is a list of values, as an action's input can be. A panel asks for one item per line. */
    val list: Boolean = false,
    /** The options of a Markdown field, or `null` for plain text. A panel edits the field as Markdown. */
    val markdown: MarkdownOptions? = null,
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

/** Grouped totals: `GET {path}/resources/{name}/aggregate`. */
@Serializable
public data class AdminAggregatePage(
    /** The groups, in order. */
    val groups: List<AdminAggregateGroup>,
    /** Whether the limit left groups out. */
    val hasMore: Boolean,
)

/** One group of an aggregate. */
@Serializable
public data class AdminAggregateGroup(
    /** The value of each group field. */
    val key: JsonObject,
    /** The value of each measure, by name, such as `count` or `sum_quantity`. */
    val values: JsonObject,
)

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
