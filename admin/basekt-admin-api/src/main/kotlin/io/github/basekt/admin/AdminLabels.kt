package io.github.basekt.admin

import io.github.basekt.data.Filter
import io.github.basekt.data.Query
import io.github.basekt.schema.FieldType
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
import io.github.basekt.server.ApiException
import io.github.basekt.server.BaseKtRuntime
import io.github.basekt.server.Identity

/** Names a record of one resource for a panel, or returns `null` to fall back to its schema label. */
public typealias LabelFunction = (Record) -> String?

/** The longest label a panel is sent; a longer one is cut and ends with `…`. */
internal const val MAX_LABEL_LENGTH: Int = 120

private val placeholder = Regex("""\{([A-Za-z][A-Za-z0-9_]*)}""")

/** The fields a label template uses: its `{field}` placeholders, or the whole template when it is a field name. */
internal fun labelFields(template: String): List<String> =
    if ('{' in template) placeholder.findAll(template).map { it.groupValues[1] }.toList() else listOf(template.trim()).filter { it.isNotEmpty() }

/**
 * Fills in [template] from [record]: each `{field}` becomes the field's value, or nothing when it is unset. Spaces
 * are collapsed, and separators left dangling by an empty value are trimmed, so `{lastName}, {firstName}` with no
 * first name is `Doe`. Returns `null` when nothing is left.
 */
internal fun renderLabel(template: String, record: Record): String? {
    val text = if ('{' in template) {
        placeholder.replace(template) { record[it.groupValues[1]]?.labelText().orEmpty() }
    } else {
        record[template.trim()]?.labelText().orEmpty()
    }
    return text.replace(Regex("\\s+"), " ").trim(' ', ',', ';', ':', '-', '|', '/', '·').takeIf { it.isNotEmpty() }
}

private fun Value.labelText(): String = when (this) {
    is Value.Items -> values.joinToString(", ") { it.labelText() }
    else -> toString()
}

private fun String.capped(): String = if (length <= MAX_LABEL_LENGTH) this else take(MAX_LABEL_LENGTH - 1).trimEnd() + "…"

/**
 * Works out how records are named in a panel: a [LabelFunction] given to `BaseKtAdmin` first, then the resource's
 * [AdminView.label], then its first text field. Records are read through the resource service, so a caller only gets
 * the labels of records they may read.
 */
internal class Labels(private val runtime: BaseKtRuntime, private val functions: Map<String, LabelFunction>) {
    /** The label of [record], a record of [resource], or `null` if nothing names it. */
    fun of(resource: Resource, record: Record): String? {
        val label = functions[resource.name]?.invoke(record)?.trim()?.takeIf { it.isNotEmpty() }
            ?: resource.adminView?.label?.let { renderLabel(it, record) }
            ?: firstText(resource, record)
        return label?.capped()
    }

    /**
     * The labels a page of [resource]'s [records] needs, keyed by resource and then id: the records' own (for a
     * picker), and those of the records their reference fields point to (for list cells). A resource [caller] may not
     * read is left out.
     */
    suspend fun forPage(resource: Resource, records: List<Record>, caller: Identity?): Map<String, Map<String, String>> {
        val out = linkedMapOf<String, MutableMap<String, String>>()
        for (record in records) {
            val id = (record[Resource.ID] as? Value.Text)?.value ?: continue
            of(resource, record)?.let { out.getOrPut(resource.name) { linkedMapOf() }[id] = it }
        }
        val wanted = linkedMapOf<String, MutableSet<String>>()
        for (field in resource.fields) {
            if (field.hidden || field.type != FieldType.REFERENCE) continue
            val target = field.target ?: continue
            for (record in records) {
                when (val value = record[field.name]) {
                    is Value.Text -> wanted.getOrPut(target) { linkedSetOf() } += value.value
                    is Value.Items -> value.values.filterIsInstance<Value.Text>().forEach { wanted.getOrPut(target) { linkedSetOf() } += it.value }
                    else -> Unit
                }
            }
        }
        for ((target, ids) in wanted) {
            val known = out[target].orEmpty().keys
            val missing = ids - known
            if (missing.isEmpty()) continue
            val labels = read(target, missing, caller)
            if (labels.isNotEmpty()) out.getOrPut(target) { linkedMapOf() }.putAll(labels)
        }
        return out
    }

    /** The labels of [resourceName]'s records with [ids] that [caller] may read, keyed by id. */
    suspend fun read(resourceName: String, ids: Collection<String>, caller: Identity?): Map<String, String> {
        val resource = runtime.schema.resource(resourceName) ?: return emptyMap()
        if (ids.isEmpty()) return emptyMap()
        val page = try {
            runtime.service.list(resource, caller, Query(filter = Filter.In(Resource.ID, ids.map { Value.Text(it) }), limit = ids.size))
        } catch (e: ApiException) {
            // Not readable by this caller, or a provided resource that can't look records up by id: no labels, so
            // the panel shows the ids.
            return emptyMap()
        }
        return page.items.mapNotNull { record ->
            val id = (record[Resource.ID] as? Value.Text)?.value ?: return@mapNotNull null
            of(resource, record)?.let { id to it }
        }.toMap()
    }

    private fun firstText(resource: Resource, record: Record): String? = resource.fields
        .filter { !it.hidden && (it.type == FieldType.STRING || it.type == FieldType.ENUM) }
        .firstNotNullOfOrNull { field -> (record[field.name] as? Value.Text)?.value?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } }
}
