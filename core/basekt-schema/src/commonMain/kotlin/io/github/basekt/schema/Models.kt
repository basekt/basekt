package io.github.basekt.schema

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import kotlin.reflect.KProperty1

/**
 * A resource described by a `@Serializable` class [T]: the same [resource] the dynamic DSL builds, plus the
 * [serializer] that converts between [T] and [Record]s. Validation, permissions, the REST API and the admin API see
 * only [resource], so typed and dynamic resources mix freely in one schema.
 *
 * Declare one with [model], or with `resource<T>("name") { }` inside [schema].
 */
public class Model<T> internal constructor(
    /** The resource, as the rest of BaseKt sees it. */
    public val resource: Resource,
    /** [T]'s serializer. */
    public val serializer: KSerializer<T>,
) {
    /** The resource's name. */
    public val name: String get() = resource.name

    /**
     * Converts [value] to a record holding every field. An id that is `null` or empty is left out, so a data source
     * generates one on create.
     */
    public fun toRecord(value: T): Record {
        val json = ModelJson.encodeToJsonElement(serializer, value).jsonObject
        return buildMap {
            (json[Resource.ID] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.content.isNotEmpty() }
                ?.let { put(Resource.ID, Value.Text(it.content)) }
            for (field in resource.fields) {
                val element = json[field.name] ?: JsonNull
                put(field.name, if (element is JsonNull) null else checkNotNull(field.decode(element)) {
                    "${serializer.descriptor.serialName}.${field.name} encoded as $element, which isn't a ${field.type.name.lowercase()}."
                })
            }
        }
    }

    /**
     * Converts [record] to a [T]. A field that is null or absent takes the property's default when it has one.
     * Throws [IllegalArgumentException] if a property with no default has no value.
     */
    public fun fromRecord(record: Record): T = try {
        val whole = resource.fields.associate { it.name to record[it.name] } + record.filterKeys { it == Resource.ID }
        ModelJson.decodeFromJsonElement(serializer, resource.encodeRecord(whole))
    } catch (e: SerializationException) {
        throw IllegalArgumentException("The record can't be read as ${serializer.descriptor.serialName}: ${e.message}", e)
    }

    override fun toString(): String = "Model(${resource.name}: ${serializer.descriptor.serialName})"
}

/** Builds a [Model] of [T] called [name]: its fields come from [T]'s serializer, and [block] adds rules and more. */
public inline fun <reified T> model(name: String, noinline block: ModelBuilder<T>.() -> Unit = {}): Model<T> =
    ModelBuilder(name, serializer<T>()).apply(block).build()

/**
 * Declares a [Model] of [T]. Each property of [T]'s serializer becomes a field, except `id`, which must be a string
 * and becomes the record's id. A property is required unless it is nullable or has a default.
 *
 * | Kotlin type | Field type |
 * |---|---|
 * | `String`, or anything serialized as a string | string |
 * | `Int`, `Long` | int, long |
 * | `Boolean` | bool |
 * | [Value.Decimal] | decimal |
 * | `kotlin.time.Instant` | instant |
 * | an enum class | enum, with the serial names as values |
 *
 * Other types are refused when the model is built. Properties are matched by their serial names, so a property
 * renamed with `@SerialName` can't be passed by reference.
 */
@SchemaDsl
public class ModelBuilder<T> @PublishedApi internal constructor(
    private val name: String,
    private val serializer: KSerializer<T>,
) {
    private val descriptor = serializer.descriptor
    private val className = descriptor.serialName
    private val fields = linkedMapOf<String, FieldBuilder>()
    private val types = mutableMapOf<String, FieldType>()
    private val values = mutableMapOf<String, List<String>>()
    private val targets = mutableMapOf<String, String>()
    private val buckets = mutableMapOf<String, String>()
    private val relations = mutableListOf<Relation>()
    private var permissions = Permissions()
    private val actions = mutableListOf<Action>()

    init {
        val id = (0 until descriptor.elementsCount).firstOrNull { descriptor.getElementName(it) == Resource.ID }
        require(id != null && descriptor.getElementDescriptor(id).kind == PrimitiveKind.STRING) {
            "$className needs an 'id' property of type String (for example `val id: String? = null`) to be a resource."
        }
        for (index in 0 until descriptor.elementsCount) {
            val property = descriptor.getElementName(index)
            if (property == Resource.ID) continue
            val element = descriptor.getElementDescriptor(index)
            types[property] = fieldType(property, element)
            if (element.kind == SerialKind.ENUM) values[property] = (0 until element.elementsCount).map(element::getElementName)
            val required = !element.isNullable && !descriptor.isElementOptional(index)
            fields[property] = FieldBuilder().apply { if (required) required() }
        }
    }

    /** Adds rules to [property]'s field, such as `field(Item::code) { pattern("[A-Z]+") }`. */
    public fun field(property: KProperty1<T, *>, block: FieldBuilder.() -> Unit) {
        builder(property.name).apply(block)
    }

    /** Makes [property] a reference to a record of [target]. */
    public fun reference(property: KProperty1<T, String?>, target: String, block: FieldBuilder.() -> Unit = {}) {
        builder(property.name).apply(block)
        targets[property.name] = target
    }

    /** Makes [property] a stored file in [bucket]: it holds the file's key, such as `images/3f1c…/lamp.jpg`. */
    public fun file(property: KProperty1<T, String?>, bucket: String, block: FieldBuilder.() -> Unit = {}) {
        builder(property.name).apply(block)
        buckets[property.name] = bucket
    }

    /**
     * Makes [property] a reference to a record of [target] and adds the relation [name], by default the property's
     * name without its `Id` suffix: `belongsTo(Item::groupId, "groups")` adds the relation `group`.
     */
    public fun belongsTo(
        property: KProperty1<T, String?>,
        target: String,
        name: String = property.name.removeSuffix("Id"),
        block: FieldBuilder.() -> Unit = {},
    ) {
        reference(property, target, block)
        relations += Relation.BelongsTo(name, target, property.name)
    }

    /** Declares that many [target] records point to this one through their reference field [foreignField]. */
    public fun hasMany(name: String, target: String, foreignField: String) {
        relations += Relation.HasMany(name, target, foreignField)
    }

    /** Sets who may read and write this resource's records. Everything not granted is denied. */
    public fun permissions(block: PermissionsBuilder.() -> Unit) {
        permissions = PermissionsBuilder(permissions).apply(block).build()
    }

    /** Declares the action [name] on [target]. Register the code that runs it with the server. See [Action]. */
    public fun action(name: String, target: ActionTarget = ActionTarget.RECORD, block: ActionBuilder.() -> Unit) {
        actions += ActionBuilder(name, target).apply(block).build()
    }

    /** Builds the model. */
    @PublishedApi
    internal fun build(): Model<T> {
        val built = fields.map { (property, builder) ->
            val target = targets[property]
            val bucket = buckets[property]
            if (bucket != null) {
                require(types[property] == FieldType.STRING) { "$className.$property must be a String to hold a file." }
                builder.build(property, FieldType.FILE, null, null, bucket)
            } else if (target != null) {
                require(types[property] == FieldType.STRING) { "$className.$property must be a String to reference '$target'." }
                builder.build(property, FieldType.REFERENCE, null, target)
            } else {
                builder.build(property, types.getValue(property), values[property], null)
            }
        }
        return Model(Resource(name, built, relations.toList(), permissions, actions.toList()), serializer)
    }

    private fun builder(property: String): FieldBuilder = fields[property]
        ?: throw IllegalArgumentException(
            if (property == Resource.ID) "$className.id is the record's id, not a field."
            else "$className has no serialized property '$property'. Properties renamed with @SerialName can't be passed by reference.",
        )

    private fun fieldType(property: String, element: SerialDescriptor): FieldType {
        val serialName = element.serialName.removeSuffix("?")
        return when {
            serialName == DecimalSerializer.descriptor.serialName -> FieldType.DECIMAL
            serialName == "kotlin.time.Instant" -> FieldType.INSTANT
            element.kind == PrimitiveKind.STRING -> FieldType.STRING
            element.kind == PrimitiveKind.INT -> FieldType.INT
            element.kind == PrimitiveKind.LONG -> FieldType.LONG
            element.kind == PrimitiveKind.BOOLEAN -> FieldType.BOOL
            element.kind == SerialKind.ENUM -> FieldType.ENUM
            else -> throw IllegalArgumentException(
                "$className.$property is a $serialName, which BaseKt can't store. Use String, Int, Long, Boolean, " +
                    "Value.Decimal, kotlin.time.Instant or an enum class.",
            )
        }
    }
}

/** Serializes [Value.Decimal] as its literal, a JSON string, so models can have exact decimal properties. */
public object DecimalSerializer : KSerializer<Value.Decimal> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("io.github.basekt.schema.Decimal", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Value.Decimal) {
        encoder.encodeString(value.literal)
    }

    override fun deserialize(decoder: Decoder): Value.Decimal {
        val text = decoder.decodeString()
        return Value.Decimal.parseOrNull(text) ?: throw SerializationException("'$text' is not a decimal number.")
    }
}

/** How models convert to and from records: every property written, and defaults standing in for missing values. */
private val ModelJson = Json {
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
    ignoreUnknownKeys = true
}
