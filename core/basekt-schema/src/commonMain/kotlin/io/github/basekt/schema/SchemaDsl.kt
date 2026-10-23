package io.github.basekt.schema

/** Marks the schema DSL, so that an inner block can't call an outer block's functions by accident. */
@DslMarker
public annotation class SchemaDsl

/** Builds a [Schema] and checks it. Throws [InvalidSchemaException] if it is invalid. */
public fun schema(block: SchemaBuilder.() -> Unit): Schema = SchemaBuilder().apply(block).build()

/** Declares the resources of a [schema]. */
@SchemaDsl
public class SchemaBuilder {
    private val resources = mutableListOf<Resource>()
    private val declaredModels = mutableListOf<Model<*>>()

    /** The typed resources declared so far, for repositories and typed hooks to find by class. */
    public val models: List<Model<*>> get() = declaredModels.toList()

    /** Declares a resource called [name]. */
    public fun resource(name: String, block: ResourceBuilder.() -> Unit) {
        resources += ResourceBuilder(name).apply(block).build()
    }

    /** Adds an already built [resource], such as one decoded from JSON. */
    public fun resource(resource: Resource) {
        resources += resource
    }

    /** Adds the typed resource [model]. */
    public fun resource(model: Model<*>) {
        resources += model.resource
        declaredModels += model
    }

    /** Declares a typed resource called [name] whose fields come from [T]; see [ModelBuilder]. Returns its model. */
    public inline fun <reified T> resource(name: String, noinline block: ModelBuilder<T>.() -> Unit = {}): Model<T> =
        model(name, block).also { resource(it) }

    /** Builds the schema and checks it. Throws [InvalidSchemaException] if it is invalid. */
    public fun build(): Schema = Schema(resources.toList()).requireValid()
}

/** Declares the fields, relations and permissions of one resource. */
@SchemaDsl
public class ResourceBuilder internal constructor(private val name: String) {
    private val fields = mutableListOf<Field>()
    private val relations = mutableListOf<Relation>()
    private var permissions = Permissions()

    /** Declares a string field. */
    public fun string(name: String, block: FieldBuilder.() -> Unit = {}): Unit = field(name, FieldType.STRING, block)

    /** Declares a 32-bit integer field. */
    public fun int(name: String, block: FieldBuilder.() -> Unit = {}): Unit = field(name, FieldType.INT, block)

    /** Declares a 64-bit integer field. */
    public fun long(name: String, block: FieldBuilder.() -> Unit = {}): Unit = field(name, FieldType.LONG, block)

    /** Declares an exact decimal field. */
    public fun decimal(name: String, block: FieldBuilder.() -> Unit = {}): Unit = field(name, FieldType.DECIMAL, block)

    /** Declares a true-or-false field. */
    public fun bool(name: String, block: FieldBuilder.() -> Unit = {}): Unit = field(name, FieldType.BOOL, block)

    /** Declares a point-in-time field. */
    public fun instant(name: String, block: FieldBuilder.() -> Unit = {}): Unit = field(name, FieldType.INSTANT, block)

    /** Declares a field whose value is one of [values]. */
    public fun enum(name: String, values: List<String>, block: FieldBuilder.() -> Unit = {}): Unit =
        field(name, FieldType.ENUM, block, values = values)

    /** Declares a field that holds the id of a record of the [target] resource. */
    public fun reference(name: String, target: String, block: FieldBuilder.() -> Unit = {}): Unit =
        field(name, FieldType.REFERENCE, block, target = target)

    /**
     * Declares a field that holds a stored file in [bucket]: its key, such as `images/3f1c…/lamp.jpg`. Install
     * `BaseKtFiles` with that bucket to upload and serve the files.
     */
    public fun file(name: String, bucket: String, block: FieldBuilder.() -> Unit = {}): Unit =
        field(name, FieldType.FILE, block, bucket = bucket)

    /**
     * Declares that this resource belongs to a [target] record: adds the reference field [field] (by default
     * `<name>Id`) and the relation [name].
     */
    public fun belongsTo(
        name: String,
        target: String,
        field: String = "${name}Id",
        block: FieldBuilder.() -> Unit = {},
    ) {
        reference(field, target, block)
        relations += Relation.BelongsTo(name, target, field)
    }

    /** Declares that many [target] records point to this one through their reference field [foreignField]. */
    public fun hasMany(name: String, target: String, foreignField: String) {
        relations += Relation.HasMany(name, target, foreignField)
    }

    /** Sets who may read and write this resource's records. Everything not granted is denied. */
    public fun permissions(block: PermissionsBuilder.() -> Unit) {
        permissions = PermissionsBuilder(permissions).apply(block).build()
    }

    private fun field(
        name: String,
        type: FieldType,
        block: FieldBuilder.() -> Unit,
        values: List<String>? = null,
        target: String? = null,
        bucket: String? = null,
    ) {
        fields += FieldBuilder().apply(block).build(name, type, values, target, bucket)
    }

    internal fun build(): Resource = Resource(name, fields.toList(), relations.toList(), permissions)
}

/** Declares whether a field is required and its [Rule]s. */
@SchemaDsl
public class FieldBuilder internal constructor() {
    private var required = false
    private val rules = mutableListOf<Rule>()

    /** Makes the field required: a record must have a non-null value. */
    public fun required() {
        required = true
    }

    /** The value must be at least [value]. */
    public fun min(value: Long) {
        rules += Rule.Min(value.toString())
    }

    /** The value must be at least [value], a decimal literal such as `"0.01"`. */
    public fun min(value: String) {
        rules += Rule.Min(value)
    }

    /** The value must be at most [value]. */
    public fun max(value: Long) {
        rules += Rule.Max(value.toString())
    }

    /** The value must be at most [value], a decimal literal. */
    public fun max(value: String) {
        rules += Rule.Max(value)
    }

    /** The text must be between [min] and [max] characters long; either bound may be `null`. */
    public fun length(min: Int? = null, max: Int? = null) {
        rules += Rule.Length(min, max)
    }

    /** The whole text must match [regex]; [message] replaces the default error message. */
    public fun pattern(regex: String, message: String? = null) {
        rules += Rule.Pattern(regex, message)
    }

    /** The [FieldValidator] registered as [name] must accept the value. */
    public fun custom(name: String) {
        rules += Rule.Custom(name)
    }

    internal fun build(name: String, type: FieldType, values: List<String>?, target: String?, bucket: String? = null): Field =
        Field(name, type, required, values, target, rules.toList(), bucket)
}

/** Declares who may perform each [Operation]. */
@SchemaDsl
public class PermissionsBuilder internal constructor(initial: Permissions) {
    private var read = initial.read
    private var create = initial.create
    private var update = initial.update
    private var delete = initial.delete

    /** Anyone, including anonymous callers. */
    public val everyone: Access get() = Access.Everyone

    /** Any authenticated caller. */
    public val authenticated: Access get() = Access.Authenticated

    /** Who may get and list records. */
    public fun read(access: Access) {
        read = access
    }

    /** Callers with any of [roles] may get and list records. */
    public fun read(vararg roles: String): Unit = read(Access.Roles(roles.toSet()))

    /** Who may create records. */
    public fun create(access: Access) {
        create = access
    }

    /** Callers with any of [roles] may create records. */
    public fun create(vararg roles: String): Unit = create(Access.Roles(roles.toSet()))

    /** Who may update records. */
    public fun update(access: Access) {
        update = access
    }

    /** Callers with any of [roles] may update records. */
    public fun update(vararg roles: String): Unit = update(Access.Roles(roles.toSet()))

    /** Who may delete records. */
    public fun delete(access: Access) {
        delete = access
    }

    /** Callers with any of [roles] may delete records. */
    public fun delete(vararg roles: String): Unit = delete(Access.Roles(roles.toSet()))

    /** Who may create, update and delete records. */
    public fun write(access: Access) {
        create = access
        update = access
        delete = access
    }

    /** Callers with any of [roles] may create, update and delete records. */
    public fun write(vararg roles: String): Unit = write(Access.Roles(roles.toSet()))

    internal fun build(): Permissions = Permissions(read, create, update, delete)
}
