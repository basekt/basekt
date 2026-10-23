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
) {
    /** Returns the field called [name], or `null` if there isn't one. */
    public fun field(name: String): Field? = fields.firstOrNull { it.name == name }

    /** Returns the relation called [name], or `null` if there isn't one. */
    public fun relation(name: String): Relation? = relations.firstOrNull { it.name == name }

    /** Constants for every resource. */
    public companion object {
        /** The name of the identifier every record has. */
        public const val ID: String = "id"
    }
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
)

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
) {
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
        if (id == null) return emptySet()
        return when (match) {
            ScopeMatch.Caller -> setOf(id)
            is ScopeMatch.Attribute -> attributes[match.name].orEmpty()
        }
    }
}

/** The caller's values that a [Scope]'s field must hold one of. */
@Serializable
public sealed interface ScopeMatch {
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
