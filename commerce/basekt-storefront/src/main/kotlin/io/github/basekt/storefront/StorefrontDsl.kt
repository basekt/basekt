package io.github.basekt.storefront

import io.github.basekt.schema.Extensible
import io.github.basekt.schema.FieldType
import io.github.basekt.schema.Relation
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.extension
import kotlinx.serialization.Serializable

/** The key a resource's [Storefront] is kept under in [Resource.extensions]. */
public const val STOREFRONT_KEY: String = "storefront"

/**
 * How one resource is sold online: which of its fields a shopper sees, and how its records are found and told apart.
 * Build one with [storefront]. Only the fields named here are ever public.
 */
@Serializable
public data class Storefront(
    /** The field that names a record in its page's address. `id` by default. */
    val slug: String = Resource.ID,
    /** The field shown as the record's name. */
    val title: String,
    /** The field holding the price, or `null` for none. */
    val price: String? = null,
    /** The field holding the address of the record's picture, or `null` for none. */
    val image: String? = null,
    /** The field shown as a short description, or `null` for none. */
    val summary: String? = null,
    /** The fields a shopper may narrow a listing by. */
    val filters: List<String> = emptyList(),
    /** The records, such as sizes, that a shopper chooses between on a detail page, or `null` for none. */
    val variants: Variants? = null,
    /** Conditions a record must meet to be listed. */
    val listWhen: List<Condition> = emptyList(),
)

/** The variants of a record: the records of its has-many relation [relation], told apart by their field [by]. */
@Serializable
public data class Variants(
    /** The name of a has-many relation of the resource. */
    val relation: String,
    /** The field of the related resource that tells variants apart, such as a size. */
    val by: String,
)

/** A condition on a field: it equals [equals], written as text and read as the field's type. */
@Serializable
public data class Condition(
    /** The field. */
    val field: String,
    /** The value, as text. */
    val equals: String,
)

/** Marks the storefront DSL, so that an inner block can't call an outer block's functions by accident. */
@DslMarker
public annotation class StorefrontDsl

/** Declares what a resource's [Storefront] shows. */
@StorefrontDsl
public class StorefrontBuilder internal constructor() {
    private var slug: String = Resource.ID
    private var title: String? = null
    private var price: String? = null
    private var image: String? = null
    private var summary: String? = null
    private var filters: List<String> = emptyList()
    private var variants: Variants? = null
    private val listWhen = mutableListOf<Condition>()

    /** The field that names a record in its page's address; `id` unless set. Pick one that is unique. */
    public fun slug(field: String) { slug = field }

    /** The field shown as the record's name. Required. */
    public fun title(field: String) { title = field }

    /** The field holding the price. */
    public fun price(field: String) { price = field }

    /** The field holding the address of the record's picture, a text field with a web address. */
    public fun image(field: String) { image = field }

    /** The field shown as a short description. */
    public fun summary(field: String) { summary = field }

    /** The fields a shopper may narrow the listing by. */
    public fun filters(vararg fields: String) { filters = fields.toList() }

    /** Offers the records of the has-many relation [relation], told apart by their field [by], on a detail page. */
    public fun variants(relation: String, by: String) { variants = Variants(relation, by) }

    /** Lists only the records that meet every condition [block] declares. */
    public fun listWhen(block: ConditionsBuilder.() -> Unit) {
        listWhen += ConditionsBuilder().apply(block).build()
    }

    internal fun build(): Storefront = Storefront(
        slug = slug,
        title = requireNotNull(title) { "A storefront needs a title field: title(\"name\")." },
        price = price,
        image = image,
        summary = summary,
        filters = filters,
        variants = variants,
        listWhen = listWhen.toList(),
    )
}

/** Declares the conditions of [StorefrontBuilder.listWhen]. */
@StorefrontDsl
public class ConditionsBuilder internal constructor() {
    private val conditions = mutableListOf<Condition>()

    /** Requires [field] to equal [value]. */
    public fun eq(field: String, value: Any) {
        conditions += Condition(field, value.toString())
    }

    internal fun build(): List<Condition> = conditions.toList()
}

/**
 * Makes this resource sold online: its records get a listing and a detail page, and a public API, under the
 * storefront's path. [block] says which fields show; nothing outside it is public. See [Storefront].
 */
public fun Extensible.storefront(block: StorefrontBuilder.() -> Unit) {
    extension(STOREFRONT_KEY, Storefront.serializer(), StorefrontBuilder().apply(block).build())
}

/** The resource's [Storefront], or `null` if it isn't sold online. */
public val Resource.storefront: Storefront? get() = extension<Storefront>(STOREFRONT_KEY)

private val textTypes = setOf(FieldType.STRING, FieldType.ENUM)
private val numberTypes = setOf(FieldType.DECIMAL, FieldType.INT, FieldType.LONG)

/** Returns what is wrong with the storefronts this schema declares. Empty means every one fits its resource. */
public fun Schema.storefrontProblems(): List<String> {
    val problems = mutableListOf<String>()
    for (resource in resources) {
        val shop = resource.storefront ?: continue
        val at = "Storefront of '${resource.name}'"
        if (resource.name == "api") problems += "$at: a resource named 'api' clashes with the storefront's API path."

        fun shown(role: String, field: String, types: Set<FieldType>, idAllowed: Boolean = false) {
            if (field == Resource.ID && idAllowed) return
            val declared = resource.field(field)
            when {
                declared == null -> {
                    val hint = (resource.relation(field) as? Relation.BelongsTo)?.let { " Name its field '${it.field}'." }.orEmpty()
                    problems += "$at: $role '$field' is not a field of '${resource.name}'.$hint"
                }
                declared.hidden -> problems += "$at: $role '$field' is hidden, so it can't be public."
                declared.list || declared.type !in types ->
                    problems += "$at: $role '$field' is a ${declared.type.name.lowercase()}${if (declared.list) " list" else ""}; it needs ${types.joinToString(" or ") { it.name.lowercase() }}."
            }
        }

        shown("slug", shop.slug, textTypes, idAllowed = true)
        shown("title", shop.title, textTypes)
        shop.price?.let { shown("price", it, numberTypes) }
        shop.image?.let { shown("image", it, setOf(FieldType.STRING)) }
        shop.summary?.let { shown("summary", it, textTypes) }
        for (filter in shop.filters) shown("filter", filter, textTypes + setOf(FieldType.REFERENCE, FieldType.BOOL))
        for (condition in shop.listWhen) {
            val declared = resource.field(condition.field)
            if (declared == null) {
                problems += "$at: listWhen names '${condition.field}', which is not a field of '${resource.name}'."
            } else if (declared.list || declared.type == FieldType.FILE || declared.type == FieldType.INSTANT) {
                problems += "$at: listWhen can't compare the ${declared.type.name.lowercase()} field '${condition.field}'."
            }
        }
        shop.variants?.let { variants ->
            val relation = resource.relation(variants.relation)
            if (relation !is Relation.HasMany) {
                problems += "$at: variants relation '${variants.relation}' is not a has-many relation of '${resource.name}'."
            } else {
                val target = resource(relation.target)
                val by = target?.field(variants.by)
                when {
                    target == null -> problems += "$at: variants relation '${variants.relation}' points at '${relation.target}', which is not in the schema."
                    by == null -> problems += "$at: variants field '${variants.by}' is not a field of '${target.name}'."
                    by.hidden || by.list || by.type !in textTypes + numberTypes ->
                        problems += "$at: variants field '${variants.by}' can't be shown to shoppers."
                }
            }
        }
    }
    return problems
}
