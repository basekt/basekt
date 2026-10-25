package io.github.basekt.schema

import kotlinx.serialization.json.JsonElement
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

/** Declares typed fields: a resource's, or an action's input. */
@SchemaDsl
public open class FieldsBuilder internal constructor() {
    internal val fields: MutableList<Field> = mutableListOf()

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
}

/** Declares the fields, relations, permissions and actions of one resource. */
@SchemaDsl
public class ResourceBuilder internal constructor(private val name: String) : FieldsBuilder(), Extensible {
    private val relations = mutableListOf<Relation>()
    private var permissions = Permissions()
    private val actions = mutableListOf<Action>()
    private var workflow: Workflow? = null
    private var group: String? = null
    private val reports = mutableListOf<Report>()
    private val extensions = linkedMapOf<String, JsonElement>()
    private var id = IdSpec()
    private var provided = false

    override fun extend(key: String, value: JsonElement) {
        extensions[key] = value
    }

    /**
     * Makes the client choose every record's id, such as a username or a slug, checked by the rules [block] sets.
     * See [IdSource.NATURAL].
     */
    public fun naturalId(block: IdBuilder.() -> Unit = {}) {
        id = IdSpec(IdSource.NATURAL, IdBuilder().apply(block).rules.toList())
    }

    /**
     * Makes each record's id out of the [fields] declared in this resource, so a record is unique per their values
     * together and is addressed by them: `keyedBy("accountId", "itemId")` gives ids such as `alice~item-7`. See
     * [IdSource.KEY].
     */
    public fun keyedBy(vararg fields: String) {
        id = IdSpec(IdSource.KEY, key = fields.toList())
    }

    /**
     * Makes the application's own code serve this resource's records, read-only, instead of the data source. Register
     * its provider with the server. See [Resource.provided].
     */
    public fun provided() {
        provided = true
    }

    /** Lists this resource under the heading [name] in an admin panel's menu. Resources without one come first. */
    public fun group(name: String) {
        group = name
    }

    /**
     * Declares that this resource belongs to a [target] record: adds the reference field [field] (by default
     * `<name>Id`) and the relation [name]. [onDelete] says what deleting the target does to this record.
     */
    public fun belongsTo(
        name: String,
        target: String,
        field: String = "${name}Id",
        onDelete: OnDelete = OnDelete.RESTRICT,
        block: FieldBuilder.() -> Unit = {},
    ) {
        reference(field, target, block)
        relations += Relation.BelongsTo(name, target, field, onDelete)
    }

    /** Declares that many [target] records point to this one through their reference field [foreignField]. */
    public fun hasMany(name: String, target: String, foreignField: String) {
        relations += Relation.HasMany(name, target, foreignField)
    }

    /** Declares the instant fields `createdAt` and `updatedAt`, which the server sets. See [Auto]. */
    public fun timestamps() {
        instant("createdAt") { createdAt() }
        instant("updatedAt") { updatedAt() }
    }

    /** Sets who may read and write this resource's records. Everything not granted is denied. */
    public fun permissions(block: PermissionsBuilder.() -> Unit) {
        permissions = PermissionsBuilder(permissions).apply(block).build()
    }

    /** Declares the action [name] on [target]. Register the code that runs it with the server. See [Action]. */
    public fun action(name: String, target: ActionTarget = ActionTarget.RECORD, block: ActionBuilder.() -> Unit) {
        actions += ActionBuilder(name, target).apply(block).build()
    }

    /**
     * Makes the enum [field], declared before, the state of a [Workflow] that starts in [initial] and changes only
     * through the transitions [block] declares.
     */
    public fun workflow(field: String, initial: String, block: WorkflowBuilder.() -> Unit) {
        workflow = WorkflowBuilder(field, initial).apply(block).build()
    }

    /** Declares the report [name], drawn as [chart]; [block] sets what it groups by and computes. See [Report]. */
    public fun report(name: String, chart: Chart = Chart.BAR, block: ReportBuilder.() -> Unit = {}) {
        reports += ReportBuilder(name, chart).apply(block).build()
    }

    internal fun build(): Resource = Resource(
        name, withState(fields, workflow), relations.toList(), permissions, actions.toList(), workflow, group, reports.toList(), extensions.toMap(), id, provided,
    )
}

/** Declares the checks on the ids a client chooses. See [IdSpec.rules]. */
@SchemaDsl
public class IdBuilder internal constructor() {
    internal val rules: MutableList<Rule> = mutableListOf()

    /** The id must be between [min] and [max] characters long; either bound may be `null`. */
    public fun length(min: Int? = null, max: Int? = null) {
        rules += Rule.Length(min, max)
    }

    /** The whole id must match [regex]; [message] replaces the default error message. */
    public fun pattern(regex: String, message: String? = null) {
        rules += Rule.Pattern(regex, message)
    }

    /** The [FieldValidator] registered as [name] must accept the id. */
    public fun custom(name: String) {
        rules += Rule.Custom(name)
    }
}

/** Marks the field of [workflow] as its [Auto.State]. */
internal fun withState(fields: List<Field>, workflow: Workflow?): List<Field> =
    fields.map { if (workflow != null && it.name == workflow.field && it.auto == null) it.copy(auto = Auto.State) else it }

/** Declares the transitions of a [Workflow]. */
@SchemaDsl
public class WorkflowBuilder internal constructor(private val field: String, private val initial: String) {
    private val transitions = mutableListOf<Transition>()

    /** Declares the transition [name] from [from] to [to]; [block] sets its input, who may run it and a confirmation. */
    public fun transition(name: String, from: String, to: String, block: ActionBuilder.() -> Unit): Unit =
        transition(name, listOf(from), to, block)

    /** Declares the transition [name] from any of [from] to [to]. */
    public fun transition(name: String, from: List<String>, to: String, block: ActionBuilder.() -> Unit) {
        val action = ActionBuilder(name, ActionTarget.RECORD).apply(block).build()
        transitions += Transition(name, from, to, action.access, action.input, action.confirm)
    }

    internal fun build(): Workflow = Workflow(field, initial, transitions.toList())
}

/** Declares an [Action]'s input, who may run it, and what to confirm first. */
@SchemaDsl
public class ActionBuilder internal constructor(private val name: String, private val target: ActionTarget) {
    private var input = emptyList<Field>()
    private var access: Access = Access.Nobody
    private var confirm: String? = null

    /** Anyone, including anonymous callers. */
    public val everyone: Access get() = Access.Everyone

    /** Any authenticated caller. */
    public val authenticated: Access get() = Access.Authenticated

    /** Declares the fields the caller sends, with the same functions as a resource's fields. */
    public fun input(block: FieldsBuilder.() -> Unit) {
        input = FieldsBuilder().apply(block).fields.toList()
    }

    /** Who may run the action. No one may until it is set. */
    public fun allow(access: Access) {
        this.access = access
    }

    /** Callers with any of [roles] may run the action. */
    public fun allow(vararg roles: String): Unit = allow(Access.Roles(roles.toSet()))

    /** A panel asks [question] before running the action. */
    public fun confirm(question: String) {
        confirm = question
    }

    internal fun build(): Action = Action(name, target, input, access, confirm)
}

/** Declares whether a field is required and its [Rule]s. */
@SchemaDsl
public class FieldBuilder internal constructor() {
    private var required = false
    private val rules = mutableListOf<Rule>()
    private var auto: Auto? = null
    private var unique = false
    private var list = false
    private var hidden = false
    private var markdown: MarkdownOptions? = null

    /** Makes the field required: a record must have a non-null value. */
    public fun required() {
        required = true
    }

    /** Makes the field a list of values of its type. Only an action's or a transition's input may be one. See [Field.list]. */
    public fun list() {
        list = true
    }

    /** Keeps the field from the API: nobody reads or writes it through a route. See [Field.hidden]. */
    public fun hidden() {
        hidden = true
    }

    /** Makes the field unique: no two records may hold the same non-null value. See [Field.unique]. */
    public fun unique() {
        unique = true
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

    /**
     * Makes the field Markdown: a string field that holds formatted text, with the options [block] sets. Its length is
     * capped at the options' maximum. See [Field.markdown].
     */
    public fun markdown(block: MarkdownBuilder.() -> Unit = {}) {
        val options = MarkdownBuilder().apply(block).build()
        markdown = options
        rules += Rule.Length(null, options.maxLength)
    }

    /** The [FieldValidator] registered as [name] must accept the value. */
    public fun custom(name: String) {
        rules += Rule.Custom(name)
    }

    /** The server sets the field to [auto] instead of the client. */
    public fun auto(auto: Auto) {
        this.auto = auto
    }

    /** The server sets the field to when the record was created. */
    public fun createdAt(): Unit = auto(Auto.CreatedAt)

    /** The server sets the field to when the record was last written. */
    public fun updatedAt(): Unit = auto(Auto.UpdatedAt)

    /** The server sets the field to the id of the caller who created the record. */
    public fun createdBy(): Unit = auto(Auto.CreatedBy)

    /** The server sets the field to the id of the caller who last wrote the record. */
    public fun updatedBy(): Unit = auto(Auto.UpdatedBy)

    /**
     * The server copies [field] of the record that the reference field [via] points to into this field when the
     * record is created. See [Auto.Inherited].
     */
    public fun inherit(via: String, field: String): Unit = auto(Auto.Inherited(via, field))

    /**
     * The server counts the record's writes in the field, and refuses an update that sends a version other than the
     * current one. See [Auto.Version].
     */
    public fun version(): Unit = auto(Auto.Version)

    /**
     * The server numbers each new record, from [start] up, without repeats or gaps. A string field gets [prefix] and
     * the number padded to [digits], such as `REF-000042`. See [Auto.Sequence].
     */
    public fun sequence(prefix: String = "", digits: Int = 0, start: Long = 1): Unit =
        auto(Auto.Sequence(prefix, digits, start))

    internal fun build(name: String, type: FieldType, values: List<String>?, target: String?, bucket: String? = null): Field =
        Field(name, type, required, values, target, rules.toList(), bucket, auto, unique, list, hidden, markdown)
}

/** Sets the [MarkdownOptions] of a field. */
@SchemaDsl
public class MarkdownBuilder internal constructor() {
    private var math = false
    private var images = false
    private var maxLength = MarkdownOptions.DEFAULT_MAX_LENGTH

    /** Allows TeX formulas: `$x^2$` inline and `$$...$$` as a block. */
    public fun math() {
        math = true
    }

    /** Allows images that point at `http`, `https` or a path on the same site. */
    public fun images() {
        images = true
    }

    /** Caps the text at [characters] characters, [MarkdownOptions.DEFAULT_MAX_LENGTH] unless set. */
    public fun maxLength(characters: Int) {
        maxLength = characters
    }

    internal fun build(): MarkdownOptions = MarkdownOptions(math, images, maxLength)
}

/** Declares who may perform each [Operation]. */
@SchemaDsl
public class PermissionsBuilder internal constructor(initial: Permissions) {
    private var read = initial.read
    private var create = initial.create
    private var update = initial.update
    private var delete = initial.delete
    private val scopes = initial.scopes.toMutableList()

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

    /** Every operation, the default of [scope] and [ownedBy]. */
    public val all: Set<Operation> get() = Operation.entries.toSet()

    /** Create, update and delete, for a scope that leaves reading open. */
    public val writes: Set<Operation> get() = setOf(Operation.CREATE, Operation.UPDATE, Operation.DELETE)

    /**
     * Limits [on] to the records whose [field] holds one of the values of the caller's [attribute], except for callers
     * with one of the [unless] roles. See [Scope].
     */
    public fun scope(field: String, attribute: String, on: Set<Operation> = all, unless: Set<String> = emptySet()) {
        scopes += Scope(field, ScopeMatch.Attribute(attribute), on, unless)
    }

    /** Limits [on] to the records whose [field] holds the caller's id, except for callers with one of the [unless] roles. */
    public fun ownedBy(field: String, on: Set<Operation> = all, unless: Set<String> = emptySet()) {
        scopes += Scope(field, ScopeMatch.Caller, on, unless)
    }

    /**
     * Limits [on] to the records that satisfy any one of the [clauses], except for callers with one of the [unless]
     * roles. Each clause is a field and the caller's values it must hold one of, such as a transfer's source or
     * destination location. See [Scope.orElse].
     */
    public fun scopeAnyOf(on: Set<Operation> = all, unless: Set<String> = emptySet(), clauses: ScopeClauses.() -> Unit) {
        val built = ScopeClauses().apply(clauses).list
        require(built.isNotEmpty()) { "A scope needs at least one clause." }
        scopes += Scope(built.first().field, built.first().match, on, unless, built.drop(1))
    }

    internal fun build(): Permissions = Permissions(read, create, update, delete, scopes.toList())
}

/** Declares the clauses of a [PermissionsBuilder.scopeAnyOf] scope. */
@SchemaDsl
public class ScopeClauses internal constructor() {
    internal val list = mutableListOf<ScopeClause>()

    /** A record is in the scope when [field] holds one of the values of the caller's [attribute]. */
    public fun attribute(field: String, attribute: String) {
        list += ScopeClause(field, ScopeMatch.Attribute(attribute))
    }

    /** A record is in the scope when [field] holds the caller's id. */
    public fun caller(field: String) {
        list += ScopeClause(field, ScopeMatch.Caller)
    }
}
