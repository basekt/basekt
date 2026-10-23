package io.github.basekt.schema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A set of [resources]: the whole data model an application exposes.
 *
 * A schema is plain data. Build one with the [schema] DSL, or read one with [Schema.fromJson]. Both check it with
 * [problems] and refuse an invalid one.
 */
@Serializable
public data class Schema(
    /** The resources, in declaration order. Names are unique. */
    val resources: List<Resource>,
) {
    /** Returns the resource called [name], or `null` if there isn't one. */
    public fun resource(name: String): Resource? = resources.firstOrNull { it.name == name }

    /** Returns every problem in this schema's definition. An empty list means the schema is valid. */
    public fun problems(): List<SchemaProblem> = SchemaChecker.check(this)

    /** Throws [InvalidSchemaException] if this schema has [problems], and returns it otherwise. */
    public fun requireValid(): Schema {
        val found = problems()
        if (found.isNotEmpty()) throw InvalidSchemaException(found)
        return this
    }

    /** Encodes this schema as JSON. */
    public fun toJson(): String = SchemaJson.encodeToString(serializer(), this)

    /** Reads schemas from JSON. */
    public companion object {
        /** Decodes a schema from [json] and checks it. Throws [InvalidSchemaException] if it is invalid. */
        public fun fromJson(json: String): Schema = SchemaJson.decodeFromString(serializer(), json).requireValid()
    }
}

/**
 * One kind of record, such as a table or a collection.
 *
 * Every record has a string [ID] that the data source generates unless a create gives one. [fields] don't list it.
 */
@Serializable
public data class Resource(
    /** The resource's name, used in routes and references. */
    val name: String,
    /** The fields, in declaration order, excluding [ID]. */
    val fields: List<Field> = emptyList(),
    /** Named links to other resources. */
    val relations: List<Relation> = emptyList(),
    /** Who may read and write records. Everything is denied unless granted. */
    val permissions: Permissions = Permissions(),
    /** The named operations beyond create, update and delete that callers may run. See [Action]. */
    val actions: List<Action> = emptyList(),
    /** The state machine of one of its enum fields, or `null`. See [Workflow]. */
    val workflow: Workflow? = null,
    /** The heading an admin panel lists this resource under, or `null` for the top of the menu. */
    val group: String? = null,
) {
    /** Returns the field called [name], or `null` if there isn't one. */
    public fun field(name: String): Field? = fields.firstOrNull { it.name == name }

    /** Returns the action called [name], or `null` if there isn't one. */
    public fun action(name: String): Action? = actions.firstOrNull { it.name == name }

    /** Returns the [workflow]'s transition called [name], or `null` if there isn't one. */
    public fun transition(name: String): Transition? = workflow?.transitions?.firstOrNull { it.name == name }

    /** Every operation callers may run on records: the [actions] and the [workflow]'s transitions. */
    public val runnableActions: List<Action> get() = actions + workflow?.transitions?.map { it.asAction() }.orEmpty()

    /** Returns the relation called [name], or `null` if there isn't one. */
    public fun relation(name: String): Relation? = relations.firstOrNull { it.name == name }

    /** Constants for every resource. */
    public companion object {
        /** The name of the identifier every record has. */
        public const val ID: String = "id"
    }
}

/**
 * A named operation on a resource, such as approving a record or moving things between two of them: one step that
 * may check rules and write several records in one transaction. The declaration is data; the server registers the
 * code that runs it.
 */
@Serializable
public data class Action(
    /** The action's name, used in routes. */
    val name: String,
    /** Whether it runs on one record or on the resource as a whole. */
    val target: ActionTarget = ActionTarget.RECORD,
    /** What the caller sends, checked like a record's fields. */
    val input: List<Field> = emptyList(),
    /** Who may run it. */
    val access: Access = Access.Nobody,
    /** A question a panel asks before running it, or `null` to run it straight away. */
    val confirm: String? = null,
) {
    /** The input as a resource of its own, for decoding and checking it like a record. */
    public val inputResource: Resource get() = Resource("$name.input", input)
}

/**
 * A state machine on one enum [field]: records start in [initial], and only [transitions] change the state. The
 * server sets the field; clients can't write it. Each transition runs like an [Action] on one record.
 */
@Serializable
public data class Workflow(
    /** The enum field that holds the state. */
    val field: String,
    /** The state of a new record. */
    val initial: String,
    /** The ways the state may change. */
    val transitions: List<Transition> = emptyList(),
)

/**
 * A named change of a [Workflow]'s state, from one of [from] to [to], that callers with [access] may run on a record.
 * Code registered for it runs first, as a guard that may refuse it and as side effects in the same transaction.
 */
@Serializable
public data class Transition(
    /** The transition's name, used in routes like an action's. */
    val name: String,
    /** The states it may start from. */
    val from: List<String>,
    /** The state it moves the record to. */
    val to: String,
    /** Who may run it. */
    val access: Access = Access.Nobody,
    /** What the caller sends, checked like a record's fields. */
    val input: List<Field> = emptyList(),
    /** A question a panel asks before running it, or `null`. */
    val confirm: String? = null,
) {
    /** The transition as the record action it runs as. */
    public fun asAction(): Action = Action(name, ActionTarget.RECORD, input, access, confirm)
}

/** What an [Action] runs on. */
@Serializable
public enum class ActionTarget {
    /** One record, named by its id. */
    @SerialName("record") RECORD,

    /** The resource as a whole, such as an import or a batch change. */
    @SerialName("resource") RESOURCE,
}

/** A typed field of a [Resource]. */
@Serializable
public data class Field(
    /** The field's name. */
    val name: String,
    /** The field's type. */
    val type: FieldType,
    /** Whether a record must have a non-null value for this field. */
    val required: Boolean = false,
    /** The allowed values, for an [FieldType.ENUM] field only. */
    val values: List<String>? = null,
    /** The referenced resource's name, for a [FieldType.REFERENCE] field only. */
    val target: String? = null,
    /** Extra checks on the field's value. */
    val rules: List<Rule> = emptyList(),
    /** The bucket the field's files are stored in, for a [FieldType.FILE] field only. */
    val bucket: String? = null,
    /** What the server sets this field to on every write, or `null` for a field the client writes. See [Auto]. */
    val auto: Auto? = null,
    /**
     * Whether no two records may hold the same non-null value in this field. A write that would repeat one is refused
     * with a [FieldErrorCode.UNIQUE] error. Null and unset values never clash.
     */
    val unique: Boolean = false,
    /**
     * Whether the field holds a list of values of its [type], as [Value.Items]. Only the input of an action or a
     * transition may be a list. [rules] and [values] apply to each item, and a required list needs at least one.
     */
    val list: Boolean = false,
)

/**
 * A value the server sets instead of the client. A client that sends one gets a `read_only` error, and a `null`
 * is ignored. The service sets it inside the write's transaction, before the before-hooks run.
 */
@Serializable
public sealed interface Auto {
    /** When the record was created, on an [FieldType.INSTANT] field. */
    @Serializable
    @SerialName("createdAt")
    public data object CreatedAt : Auto

    /** When the record was last written, on an [FieldType.INSTANT] field. Set on create and on every update. */
    @Serializable
    @SerialName("updatedAt")
    public data object UpdatedAt : Auto

    /** The id of the caller who created the record, on a string or reference field. Unset for anonymous callers. */
    @Serializable
    @SerialName("createdBy")
    public data object CreatedBy : Auto

    /** The id of the caller who last wrote the record, on a string or reference field. */
    @Serializable
    @SerialName("updatedBy")
    public data object UpdatedBy : Auto

    /**
     * The state of a [Workflow], on its enum field: its initial state on create, and changed by transitions only.
     */
    @Serializable
    @SerialName("state")
    public data object State : Auto

    /**
     * How many times the record was written, on an int or long field: 1 on create, one more on every update. An
     * update may send the version it last read in this field; if the record has moved on since, the update is refused
     * as a conflict instead of overwriting someone else's change.
     */
    @Serializable
    @SerialName("version")
    public data object Version : Auto

    /**
     * The next number of the field's own counter, set once on create. The numbers start at [start], go up by one, and
     * neither repeat nor skip: a write that fails gives its number back. A string field holds [prefix] and the number
     * padded with zeros to [digits]; an int or long field holds the number.
     */
    @Serializable
    @SerialName("sequence")
    public data class Sequence(
        /** Text before the number, for a string field. */
        val prefix: String = "",
        /** The least number of digits, padded with zeros, for a string field. 0 means no padding. */
        val digits: Int = 0,
        /** The first number. */
        val start: Long = 1,
    ) : Auto {
        /** Returns the value of a field of [type] for the number [n]. */
        public fun valueOf(n: Long, type: FieldType): Value = when (type) {
            FieldType.STRING -> Value.Text(prefix + n.toString().padStart(digits, '0'))
            else -> Value.Integer(n)
        }
    }
}

/** The type of a [Field], which decides its [Value] kind. */
@Serializable
public enum class FieldType {
    /** Text, as [Value.Text]. */
    @SerialName("string") STRING,

    /** A 32-bit integer, as [Value.Integer]. */
    @SerialName("int") INT,

    /** A 64-bit integer, as [Value.Integer]. */
    @SerialName("long") LONG,

    /** An exact decimal number, as [Value.Decimal]. */
    @SerialName("decimal") DECIMAL,

    /** True or false, as [Value.Bool]. */
    @SerialName("bool") BOOL,

    /** A point in time, as [Value.Timestamp]. */
    @SerialName("instant") INSTANT,

    /** One of the field's [Field.values], as [Value.Text]. */
    @SerialName("enum") ENUM,

    /** The id of a record of the field's [Field.target] resource, as [Value.Text]. */
    @SerialName("reference") REFERENCE,

    /**
     * A stored file: its key in the field's [Field.bucket], such as `images/3f1c…/lamp.jpg`, as [Value.Text]. The
     * bytes live elsewhere; `basekt-files` stores and serves them.
     */
    @SerialName("file") FILE,
}

/** A named link from one resource to another. */
@Serializable
public sealed interface Relation {
    /** The relation's name, unique within its resource. */
    public val name: String

    /** The resource on the other side. */
    public val target: String

    /** This record holds the other's id in its reference [field]. */
    @Serializable
    @SerialName("belongsTo")
    public data class BelongsTo(
        override val name: String,
        override val target: String,
        /** The reference field on this resource that holds the target's id. */
        val field: String,
    ) : Relation

    /** Many records of [target] hold this record's id in their reference field [foreignField]. */
    @Serializable
    @SerialName("hasMany")
    public data class HasMany(
        override val name: String,
        override val target: String,
        /** The reference field on [target] that holds this record's id. */
        val foreignField: String,
    ) : Relation
}

/** Who may perform each operation on a resource's records. */
@Serializable
public data class Permissions(
    /** Who may get and list records. */
    val read: Access = Access.Nobody,
    /** Who may create records. */
    val create: Access = Access.Nobody,
    /** Who may update records. */
    val update: Access = Access.Nobody,
    /** Who may delete records. */
    val delete: Access = Access.Nobody,
    /**
     * Which records each caller may reach, once [read], [create], [update] or [delete] let them in. Every scope that
     * applies narrows the records further. Empty means every record.
     */
    val scopes: List<Scope> = emptyList(),
) {
    /** Returns the access rule for [operation]. */
    public fun of(operation: Operation): Access = when (operation) {
        Operation.READ -> read
        Operation.CREATE -> create
        Operation.UPDATE -> update
        Operation.DELETE -> delete
    }
}

/** An operation on a resource's records that [Permissions] control. */
@Serializable
public enum class Operation {
    /** Get and list. */
    @SerialName("read") READ,

    /** Create. */
    @SerialName("create") CREATE,

    /** Update. */
    @SerialName("update") UPDATE,

    /** Delete. */
    @SerialName("delete") DELETE,
}

/** Who an operation is open to. */
@Serializable
public sealed interface Access {
    /**
     * Whether a caller with [roles] may perform the operation. `null` roles means an anonymous caller; an empty set
     * means an authenticated caller with no roles.
     */
    public fun allows(roles: Set<String>?): Boolean

    /** Anyone, including anonymous callers. */
    @Serializable
    @SerialName("everyone")
    public data object Everyone : Access {
        override fun allows(roles: Set<String>?): Boolean = true
    }

    /** Any authenticated caller. */
    @Serializable
    @SerialName("authenticated")
    public data object Authenticated : Access {
        override fun allows(roles: Set<String>?): Boolean = roles != null
    }

    /** Authenticated callers with at least one of [roles]. */
    @Serializable
    @SerialName("roles")
    public data class Roles(
        /** The roles that grant access. */
        val roles: Set<String>,
    ) : Access {
        override fun allows(roles: Set<String>?): Boolean = roles != null && roles.any { it in this.roles }
    }

    /** No one. */
    @Serializable
    @SerialName("nobody")
    public data object Nobody : Access {
        override fun allows(roles: Set<String>?): Boolean = false
    }
}

/**
 * Limits an operation to the records whose [field] holds one of the caller's own values, such as the records of the
 * caller's team or the ones they wrote. A caller who has no such value reaches no records.
 *
 * A scope applies to callers for [operations], except callers with one of the [unless] roles, who reach every record.
 */
@Serializable
public data class Scope(
    /** The field to compare: a string, enum or reference field, or [Resource.ID]. */
    val field: String,
    /** The caller's values the field must hold one of. */
    val match: ScopeMatch,
    /** The operations it narrows. */
    val operations: Set<Operation> = Operation.entries.toSet(),
    /** The roles it doesn't apply to. */
    val unless: Set<String> = emptySet(),
    /**
     * Other ways into the same scope. A record is in the scope when it satisfies [field] and [match], or any one of
     * these, such as a transfer that a caller reaches from either its source or its destination. Empty means [field]
     * alone.
     */
    val orElse: List<ScopeClause> = emptyList(),
) {
    /** Every way into the scope: the main [field] and [match], then [orElse]. */
    public val clauses: List<ScopeClause>
        get() = listOf(ScopeClause(this.field, match)) + orElse

    /**
     * Like [valuesFor], for every one of the [clauses]: the field and the values it may hold for the caller, in order,
     * or `null` if this scope doesn't apply to them.
     */
    public fun clauseValuesFor(
        operation: Operation,
        id: String?,
        roles: Set<String>,
        attributes: Map<String, Set<String>>,
    ): List<Pair<String, Set<String>>>? {
        if (operation !in operations || roles.any { it in unless }) return null
        return clauses.map { it.field to it.match.valuesOf(id, attributes) }
    }

    /**
     * Returns the values [field] may hold for a caller with [id], [roles] and [attributes] performing [operation], or
     * `null` if this scope doesn't apply to them. `null` [id] means an anonymous caller, who has no values.
     */
    public fun valuesFor(
        operation: Operation,
        id: String?,
        roles: Set<String>,
        attributes: Map<String, Set<String>>,
    ): Set<String>? {
        if (operation !in operations || roles.any { it in unless }) return null
        return match.valuesOf(id, attributes)
    }
}

/** One way into a [Scope]: a [field] that must hold one of the caller's values given by [match]. */
@Serializable
public data class ScopeClause(
    /** The field to compare: a string, enum or reference field, or [Resource.ID]. */
    val field: String,
    /** The caller's values the field must hold one of. */
    val match: ScopeMatch,
)

/** The caller's values that a [Scope]'s field must hold one of. */
@Serializable
public sealed interface ScopeMatch {
    /** Returns this match's values for a caller with [id] and [attributes]. An anonymous caller (`null` [id]) has none. */
    public fun valuesOf(id: String?, attributes: Map<String, Set<String>>): Set<String> = when {
        id == null -> emptySet()
        this is Attribute -> attributes[name].orEmpty()
        else -> setOf(id)
    }

    /** The caller's own id, for records a caller owns. */
    @Serializable
    @SerialName("caller")
    public data object Caller : ScopeMatch

    /** The values of the caller's attribute called [name], such as the teams or sites they belong to. */
    @Serializable
    @SerialName("attribute")
    public data class Attribute(
        /** The attribute's name. */
        val name: String,
    ) : ScopeMatch
}

/** An extra check on a field's value, beyond its type and [Field.required]. */
@Serializable
public sealed interface Rule {
    /** A number field's value is at least [value], a decimal literal. */
    @Serializable
    @SerialName("min")
    public data class Min(
        /** The lower bound, inclusive, as a decimal literal such as `"0"` or `"0.01"`. */
        val value: String,
    ) : Rule

    /** A number field's value is at most [value], a decimal literal. */
    @Serializable
    @SerialName("max")
    public data class Max(
        /** The upper bound, inclusive, as a decimal literal. */
        val value: String,
    ) : Rule

    /** A string field's length is within [min] and [max], both inclusive and optional. */
    @Serializable
    @SerialName("length")
    public data class Length(
        /** The shortest allowed length, or `null` for no lower bound. */
        val min: Int? = null,
        /** The longest allowed length, or `null` for no upper bound. */
        val max: Int? = null,
    ) : Rule

    /** A string field's whole value matches the regular expression [regex]. */
    @Serializable
    @SerialName("pattern")
    public data class Pattern(
        /** The regular expression the whole value must match. */
        val regex: String,
        /** The message to report instead of the default one. */
        val message: String? = null,
    ) : Rule

    /** The [FieldValidator] registered as [name] accepts the value. */
    @Serializable
    @SerialName("custom")
    public data class Custom(
        /** The name the validator is registered under. */
        val name: String,
    ) : Rule
}

/**
 * A check that a schema can name with [Rule.Custom] but can't express as data. Applications register validators by
 * name and pass them to [validateRecord].
 */
public fun interface FieldValidator {
    /** Returns a message explaining why [value] is invalid, or `null` if it is valid. */
    public fun validate(value: Value): String?
}
