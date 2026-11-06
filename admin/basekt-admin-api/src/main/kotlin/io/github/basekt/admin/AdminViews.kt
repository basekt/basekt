package io.github.basekt.admin

import io.github.basekt.schema.Extensible
import io.github.basekt.schema.FieldType
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.extension
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.reflect.KProperty1

/** The key a resource's [AdminView] is kept under in [Resource.extensions]. */
internal const val ADMIN_VIEW_KEY: String = "admin.view"

/**
 * How an admin panel presents a resource's list and record detail: optional [cards] for a grid toggle alongside the
 * table, a [detailLayout] for opening a record inline or on its own page, and an [orderField] for drag-and-drop or
 * button reordering.
 */
@Serializable
public data class AdminView(
    /** Card slots for a grid view toggle on the list screen, or `null` for table-only. */
    val cards: AdminCardView? = null,
    /** Where selecting a record opens its detail and actions. */
    val detailLayout: DetailLayout = DetailLayout.PAGE,
    /** An integer field (`int` or `long`) that orders records when the caller drags or moves rows, or `null`. */
    val orderField: String? = null,
    /**
     * How a record is named where another record points to it (list cells, detail views, pickers): a field name such
     * as `name`, or a template such as `{lastName}, {firstName}`. `null` names a record by its first text field. A
     * label function given to `BaseKtAdmin` wins over it.
     */
    val label: String? = null,
    /**
     * The fields a record's form shows in a column beside the others on a wide screen, such as its references, kind and
     * flags, so the text being written keeps the width and the page stays short. On a narrow screen they follow the rest.
     */
    val aside: List<String> = emptyList(),
    /** The fields a record's form keeps closed until they have a value, behind an "Add" button: ones rarely filled in. */
    val folded: List<String> = emptyList(),
    /** How a record's form lays out the items of a list field, by field; a list not named here is [ListStyle.CARDS]. */
    val listStyles: Map<String, ListStyle> = emptyMap(),
)

/** How a record's form lays out the items of a list field. */
@Serializable
public enum class ListStyle {
    /** A card per item, its fields stacked: for items of a few fields. */
    @SerialName("cards") CARDS,

    /** A line per item, its fields side by side: for short items read together, such as a question's options. */
    @SerialName("rows") ROWS,

    /** A tab per item, one shown at a time, and a tab that adds one: for long items, such as a lesson's sections. */
    @SerialName("tabs") TABS,
}

/** Which fields of a record populate each card when a resource's list is switched to grid view. */
@Serializable
public data class AdminCardView(
    /** A `file` or `string` field holding a stored file key or image path/URL for the card's thumbnail, or `null`. */
    val media: String? = null,
    /** The field (or `id`) shown as the card's heading, or `null` to fall back to `id`. */
    val title: String? = null,
    /** Secondary text shown beneath the heading, or `null`. */
    val subtitle: String? = null,
    /** A short status or category field shown as a badge on the card, or `null`. */
    val badge: String? = null,
)

/** How an admin panel opens a selected record from its list. */
@Serializable
public enum class DetailLayout {
    /** Navigates to `/r/{resource}/{id}` as a dedicated page. */
    @SerialName("page") PAGE,

    /** Opens a side-by-side inspector panel beside the list on wide viewports, and a drawer on narrow ones. */
    @SerialName("split") SPLIT,

    /** Opens a slide-over drawer over the list at every viewport width. */
    @SerialName("sheet") SHEET,
}

/** Configures card slots in [AdminViewBuilder.cards]. */
public class CardViewBuilder internal constructor() {
    private var media: String? = null
    private var title: String? = null
    private var subtitle: String? = null
    private var badge: String? = null

    /** Sets the card's thumbnail field (`file` or `string`). */
    public fun media(field: String) {
        media = field
    }

    /** Sets the card's thumbnail field from a typed model property. */
    public fun media(property: KProperty1<*, *>) {
        media = property.name
    }

    /** Sets the card's heading field (or `id`). */
    public fun title(field: String) {
        title = field
    }

    /** Sets the card's heading field from a typed model property. */
    public fun title(property: KProperty1<*, *>) {
        title = property.name
    }

    /** Sets the card's secondary line field. */
    public fun subtitle(field: String) {
        subtitle = field
    }

    /** Sets the card's secondary line field from a typed model property. */
    public fun subtitle(property: KProperty1<*, *>) {
        subtitle = property.name
    }

    /** Sets the card's status or category badge field. */
    public fun badge(field: String) {
        badge = field
    }

    /** Sets the card's status or category badge field from a typed model property. */
    public fun badge(property: KProperty1<*, *>) {
        badge = property.name
    }

    internal fun build(): AdminCardView = AdminCardView(media, title, subtitle, badge)
}

/** Configures a resource's [AdminView] in [adminView]. */
public class AdminViewBuilder internal constructor() {
    private var cards: AdminCardView? = null
    private var orderField: String? = null
    private var label: String? = null
    private val aside = mutableListOf<String>()
    private val folded = mutableListOf<String>()
    private val listStyles = linkedMapOf<String, ListStyle>()

    /** How selecting a record opens its detail view. [DetailLayout.PAGE] by default. */
    public var detailLayout: DetailLayout = DetailLayout.PAGE

    /** Enables a table/grid toggle on the list, mapping card slots to field names (or `id` for text slots). */
    public fun cards(
        media: String? = null,
        title: String? = null,
        subtitle: String? = null,
        badge: String? = null,
    ) {
        cards = AdminCardView(media, title, subtitle, badge)
    }

    /** Enables a table/grid toggle on the list, configuring card slots by field name or typed property reference. */
    public fun cards(block: CardViewBuilder.() -> Unit) {
        cards = CardViewBuilder().apply(block).build()
    }

    /** Enables row/card reordering by patching the `int` or `long` field [field]. */
    public fun reorderable(field: String) {
        orderField = field
    }

    /** Enables row/card reordering by patching the `int` or `long` property [property]. */
    public fun reorderable(property: KProperty1<*, *>) {
        orderField = property.name
    }

    /**
     * Names a record where another record points to it: a field name, such as `label("name")`, or a template whose
     * `{field}` placeholders are filled in, such as `label("{lastName}, {firstName}")`.
     */
    public fun label(template: String) {
        label = template
    }

    /** Shows [fields] in a column beside the others in a record's form, on a wide screen. See [AdminView.aside]. */
    public fun aside(vararg fields: String) {
        aside += fields
    }

    /** Keeps [fields] closed in a record's form until they have a value. See [AdminView.folded]. */
    public fun folded(vararg fields: String) {
        folded += fields
    }

    /** Lays out the items of the list field [field] as [style] in a record's form. See [ListStyle]. */
    public fun listStyle(field: String, style: ListStyle) {
        listStyles[field] = style
    }

    internal fun build(): AdminView = AdminView(cards, detailLayout, orderField, label, aside.toList(), folded.toList(), listStyles.toMap())
}

/**
 * Configures how the admin panel displays this resource's list and detail views:
 *
 * ```kotlin
 * resource("exhibits") {
 *     file("photo", "media")
 *     string("title") { required() }
 *     string("room")
 *     int("position") { required() }
 *     adminView {
 *         cards(media = "photo", title = "title", subtitle = "room")
 *         detailLayout = DetailLayout.SPLIT
 *         reorderable("position")
 *     }
 * }
 * ```
 */
public fun Extensible.adminView(block: AdminViewBuilder.() -> Unit) {
    extension(ADMIN_VIEW_KEY, AdminView.serializer(), AdminViewBuilder().apply(block).build())
}

/** The resource's admin display hints, or `null` when none were declared. */
public val Resource.adminView: AdminView? get() = extension<AdminView>(ADMIN_VIEW_KEY)

/** Returns what is wrong with the [AdminView]s this schema's resources declare. Empty means every one is valid. */
public fun Schema.adminViewProblems(): List<String> {
    val problems = mutableListOf<String>()
    for (resource in resources) {
        val view = resource.adminView ?: continue
        val at = "Admin view of '${resource.name}'"
        view.cards?.let { cards ->
            if (cards.media == null && cards.title == null && cards.subtitle == null && cards.badge == null) {
                problems += "$at declares cards() without naming any slot (media, title, subtitle or badge)."
            }
            cards.media?.let { name ->
                val field = resource.field(name)
                when {
                    field == null -> problems += "$at names media '$name', which isn't a field of '${resource.name}'."
                    field.hidden -> problems += "$at names media '$name', a hidden field."
                    field.type != FieldType.FILE && field.type != FieldType.STRING ->
                        problems += "$at names media '$name' (${field.type.name.lowercase()}), which must be a file or string field."
                }
            }
            for ((slot, name) in listOf("title" to cards.title, "subtitle" to cards.subtitle, "badge" to cards.badge)) {
                if (name == null || name == Resource.ID) continue
                val field = resource.field(name)
                when {
                    field == null -> problems += "$at names $slot '$name', which isn't a field of '${resource.name}'."
                    field.hidden -> problems += "$at names $slot '$name', a hidden field."
                }
            }
        }
        view.label?.let { template ->
            val names = labelFields(template)
            if (names.isEmpty()) problems += "$at has a label with no field in it: name a field, or use {field} placeholders."
            for (name in names) {
                if (name == Resource.ID) continue
                val field = resource.field(name)
                when {
                    field == null -> problems += "$at's label names '$name', which isn't a field of '${resource.name}'."
                    field.hidden -> problems += "$at's label names '$name', a hidden field."
                }
            }
        }
        for ((kind, names) in listOf("aside" to view.aside, "folded" to view.folded)) {
            for (name in names) {
                val field = resource.field(name)
                when {
                    field == null -> problems += "$at puts '$name' $kind, which isn't a field of '${resource.name}'."
                    field.hidden -> problems += "$at puts '$name' $kind, a hidden field."
                    field.auto != null -> problems += "$at puts '$name' $kind, which the server sets, so a form never shows it."
                }
            }
            names.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { problems += "$at puts '$it' $kind twice." }
        }
        for (name in view.listStyles.keys) {
            val field = resource.field(name)
            when {
                field == null -> problems += "$at gives '$name' a list style, but it isn't a field of '${resource.name}'."
                !field.list -> problems += "$at gives '$name' a list style, but it isn't a list."
                field.hidden -> problems += "$at gives '$name' a list style, but it is hidden."
            }
        }
        if (view.folded.any { it in view.aside }) problems += "$at puts ${view.folded.filter { it in view.aside }.joinToString()} both aside and folded."
        view.orderField?.let { name ->
            val field = resource.field(name)
            when {
                resource.provided -> problems += "$at marks '${resource.name}' reorderable, but provided resources are read-only."
                field == null -> problems += "$at names orderField '$name', which isn't a field of '${resource.name}'."
                field.hidden -> problems += "$at names orderField '$name', a hidden field."
                field.auto != null -> problems += "$at names orderField '$name', which is set automatically by the server."
                field.type != FieldType.INT && field.type != FieldType.LONG ->
                    problems += "$at names orderField '$name' (${field.type.name.lowercase()}), which must be an int or long field."
            }
        }
    }
    return problems
}
