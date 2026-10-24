package io.github.basekt.storefront

import io.github.basekt.data.Comparison
import io.github.basekt.data.DataSource
import io.github.basekt.data.Direction
import io.github.basekt.data.Filter
import io.github.basekt.data.InvalidQueryException
import io.github.basekt.data.Query
import io.github.basekt.data.Sort
import io.github.basekt.schema.FieldType
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Says which variants of a record, such as which sizes, can be sold now. A shop that tracks stock by the unit or by
 * count answers from its own data; the storefront only asks.
 */
public fun interface Availability {
    /**
     * Returns the ids of the records among [variants], of the resource [resource], that can be sold now. Records the
     * result leaves out are shown as sold out.
     */
    public suspend fun available(resource: Resource, variants: List<Record>): Set<String>

    /** Constants for every availability. */
    public companion object {
        /** Everything can be sold. */
        public val Always: Availability = Availability { _, variants -> variants.mapNotNull { (it[Resource.ID] as? Value.Text)?.value }.toSet() }
    }
}

/** A request the shop can't serve as asked: [status] is the HTTP status that fits it. */
internal class ShopException(val status: Int, message: String) : RuntimeException(message)

/** What a shopper asked a listing for. */
internal class ListRequest(
    val search: String?,
    val filters: Map<String, String>,
    val sort: String?,
    val limit: Int?,
    val cursor: String?,
)

/** One listed record, with only the fields the storefront shows. */
internal class ShopItem(val slug: String, val values: Map<String, Value?>)

internal class ShopPage(val items: List<ShopItem>, val nextCursor: String?)

internal class ShopVariant(val id: String, val label: Value?, val available: Boolean)

internal class ShopDetail(val item: ShopItem, val variants: List<ShopVariant>)

/** Reads the records a storefront shows, and only the fields it names. */
internal class Shop(
    private val schema: Schema,
    private val data: DataSource,
    private val availability: Availability,
    private val defaultPageSize: Int,
    private val maxPageSize: Int,
) {
    /** The resources sold online, in schema order. */
    val resources: List<Resource> = schema.resources.filter { it.storefront != null }

    fun resource(name: String): Resource? = resources.firstOrNull { it.name == name }

    suspend fun list(resource: Resource, request: ListRequest): ShopPage {
        val shop = resource.storefront!!
        val conditions = mutableListOf<Filter>()
        conditions += shop.listWhen.map { Filter.Compare(it.field, Comparison.EQ, resource.parse(it.field, it.equals)) }
        for ((field, text) in request.filters) {
            if (field !in shop.filters) throw ShopException(400, "'$field' is not a filter of '${resource.name}'.")
            conditions += Filter.Compare(field, Comparison.EQ, resource.parse(field, text))
        }
        request.search?.takeIf { it.isNotBlank() }?.let { conditions += Filter.Contains(shop.title, it.trim()) }
        val limit = (request.limit ?: defaultPageSize)
        if (limit < 1) throw ShopException(400, "'limit' must be at least 1.")
        val query = Query(
            filter = when (conditions.size) {
                0 -> null
                1 -> conditions.single()
                else -> Filter.And(conditions)
            },
            sort = listOf(sortOf(shop, request.sort)),
            limit = minOf(limit, maxPageSize),
            cursor = request.cursor,
        )
        val page = try {
            data.list(resource, query)
        } catch (e: InvalidQueryException) {
            throw ShopException(400, e.message ?: "The listing can't be read like that.")
        }
        return ShopPage(page.items.map { item(resource, shop, it) }, page.nextCursor)
    }

    suspend fun detail(resource: Resource, slug: String): ShopDetail? {
        val shop = resource.storefront!!
        val conditions = shop.listWhen.map { Filter.Compare(it.field, Comparison.EQ, resource.parse(it.field, it.equals)) } +
            Filter.Compare(shop.slug, Comparison.EQ, Value.Text(slug))
        val found = data.list(resource, Query(filter = Filter.And(conditions), limit = 1)).items.firstOrNull() ?: return null
        return ShopDetail(item(resource, shop, found), variants(resource, shop, found))
    }

    /** The choices the filters of [resource] offer: field to (value, label) pairs. Only choices that can be listed. */
    suspend fun filterOptions(resource: Resource): Map<String, List<Pair<String, String>>> {
        val shop = resource.storefront!!
        val options = linkedMapOf<String, List<Pair<String, String>>>()
        for (name in shop.filters) {
            val field = resource.field(name) ?: continue
            when (field.type) {
                FieldType.ENUM -> options[name] = field.values.orEmpty().map { it to it }
                FieldType.BOOL -> options[name] = listOf("true" to "Yes", "false" to "No")
                FieldType.REFERENCE -> {
                    val target = schema.resource(field.target ?: continue) ?: continue
                    val label = target.fields.firstOrNull { it.type == FieldType.STRING && !it.hidden }?.name
                    val records = data.list(target, Query(sort = listOfNotNull(label?.let { Sort(it) }), limit = 200)).items
                    options[name] = records.mapNotNull { record ->
                        val id = (record[Resource.ID] as? Value.Text)?.value ?: return@mapNotNull null
                        id to ((label?.let { record[it] } as? Value.Text)?.value ?: id)
                    }
                }
                else -> options[name] = emptyList()
            }
        }
        return options
    }

    private suspend fun variants(resource: Resource, shop: Storefront, record: Record): List<ShopVariant> {
        val variants = shop.variants ?: return emptyList()
        val relation = resource.relation(variants.relation) as io.github.basekt.schema.Relation.HasMany
        val target = schema.resource(relation.target) ?: return emptyList()
        val id = (record[Resource.ID] as? Value.Text)?.value ?: return emptyList()
        val rows = data.list(
            target,
            Query(filter = Filter.Compare(relation.foreignField, Comparison.EQ, Value.Text(id)), sort = listOf(Sort(variants.by)), limit = 200),
        ).items
        val sellable = availability.available(target, rows)
        return rows.mapNotNull { row ->
            val rowId = (row[Resource.ID] as? Value.Text)?.value ?: return@mapNotNull null
            ShopVariant(rowId, row[variants.by], rowId in sellable)
        }
    }

    private fun item(resource: Resource, shop: Storefront, record: Record): ShopItem {
        val shown = linkedSetOf(Resource.ID, shop.slug, shop.title)
        shop.price?.let(shown::add)
        shop.image?.let(shown::add)
        shop.summary?.let(shown::add)
        shown += shop.filters
        val values = shown.associateWith { record[it] }
        val slug = (record[shop.slug] as? Value)?.toString().orEmpty()
        return ShopItem(slug, values)
    }

    private fun sortOf(shop: Storefront, sort: String?): Sort = when (sort) {
        null, "", "title" -> Sort(shop.title, Direction.ASC)
        "-title" -> Sort(shop.title, Direction.DESC)
        "price" -> Sort(shop.price ?: throw ShopException(400, "This shop has no price to sort by."), Direction.ASC)
        "-price" -> Sort(shop.price ?: throw ShopException(400, "This shop has no price to sort by."), Direction.DESC)
        else -> throw ShopException(400, "Sort by title, -title, price or -price, not '$sort'.")
    }
}

/** Reads [text] as a value of [field]'s type, or refuses with a 400. */
internal fun Resource.parse(field: String, text: String): Value = when (field(field)?.type ?: FieldType.STRING) {
    FieldType.STRING, FieldType.ENUM, FieldType.REFERENCE, FieldType.FILE -> Value.Text(text)
    FieldType.INT, FieldType.LONG -> Value.Integer(text.toLongOrNull() ?: throw ShopException(400, "'$field' needs a whole number."))
    FieldType.DECIMAL -> Value.Decimal.parseOrNull(text) ?: throw ShopException(400, "'$field' needs a number.")
    FieldType.BOOL -> Value.Bool(text.toBooleanStrictOrNull() ?: throw ShopException(400, "'$field' needs true or false."))
    FieldType.INSTANT -> throw ShopException(400, "'$field' can't be filtered.")
}

internal fun Value?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is Value.Text -> JsonPrimitive(value)
    is Value.Integer -> JsonPrimitive(value)
    is Value.Decimal -> JsonPrimitive(literal)
    is Value.Bool -> JsonPrimitive(value)
    is Value.Timestamp -> JsonPrimitive(value.toString())
    is Value.Items -> JsonArray(values.map { it.toJson() })
}

internal fun ShopItem.toJson(): JsonObject =
    JsonObject(linkedMapOf<String, JsonElement>("slug" to JsonPrimitive(slug)).also { map -> values.forEach { (k, v) -> map[k] = v.toJson() } })
