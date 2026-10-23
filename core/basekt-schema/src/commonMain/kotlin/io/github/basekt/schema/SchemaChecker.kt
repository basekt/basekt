package io.github.basekt.schema

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A mistake in a schema's definition, at [path] such as `resources.books.fields.title`. */
@Serializable
public data class SchemaProblem(
    /** Where the problem is, as a dotted path of names. */
    val path: String,
    /** What is wrong, in a sentence. */
    val message: String,
)

/** Thrown when a schema with [problems] is built, decoded or installed. */
public class InvalidSchemaException(
    /** Every problem found. */
    public val problems: List<SchemaProblem>,
) : IllegalArgumentException(
    "Invalid schema:\n" + problems.joinToString("\n") { "  ${it.path}: ${it.message}" },
)

/** The JSON format of schemas: a `kind` property tells the variants of rules, relations and access apart. */
internal val SchemaJson: Json = Json {
    classDiscriminator = "kind"
    ignoreUnknownKeys = false
    prettyPrint = false
}

private val namePattern = Regex("[A-Za-z][A-Za-z0-9_]*")

/** A bucket name: the first segment of a file's key. */
private val bucketPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

internal object SchemaChecker {
    fun check(schema: Schema): List<SchemaProblem> {
        val problems = mutableListOf<SchemaProblem>()
        fun problem(path: String, message: String) = problems.add(SchemaProblem(path, message))

        if (schema.resources.isEmpty()) problem("resources", "A schema needs at least one resource.")
        duplicates(schema.resources.map { it.name }).forEach { problem("resources.$it", "Resource '$it' is declared twice.") }

        for (resource in schema.resources) {
            val at = "resources.${resource.name}"
            if (!namePattern.matches(resource.name)) problem(at, "'${resource.name}' is not a valid name: use letters, digits and '_', starting with a letter.")
            duplicates(resource.fields.map { it.name }).forEach { problem("$at.fields.$it", "Field '$it' is declared twice.") }
            for (field in resource.fields) checkField(schema, field, "$at.fields.${field.name}", ::problem)
            duplicates(resource.relations.map { it.name }).forEach { problem("$at.relations.$it", "Relation '$it' is declared twice.") }
            for (relation in resource.relations) checkRelation(schema, resource, relation, "$at.relations.${relation.name}", ::problem)
        }
        return problems
    }

    private fun checkField(schema: Schema, field: Field, at: String, problem: (String, String) -> Unit) {
        if (!namePattern.matches(field.name)) problem(at, "'${field.name}' is not a valid name: use letters, digits and '_', starting with a letter.")
        if (field.name == Resource.ID) problem(at, "'${Resource.ID}' is reserved: every record has it already.")
        when (field.type) {
            FieldType.ENUM -> when {
                field.values.isNullOrEmpty() -> problem(at, "An enum field needs at least one value.")
                field.values.size != field.values.toSet().size -> problem(at, "An enum field's values must be unique.")
            }
            FieldType.REFERENCE -> when {
                field.target == null -> problem(at, "A reference field needs a target resource.")
                schema.resource(field.target) == null -> problem(at, "The target resource '${field.target}' doesn't exist.")
            }
            FieldType.FILE -> if (field.bucket == null || !bucketPattern.matches(field.bucket)) {
                problem(at, "A file field needs a bucket name of letters, digits, '.', '_' and '-', starting with a letter or digit.")
            }
            else -> Unit
        }
        if (field.type != FieldType.ENUM && field.values != null) problem(at, "Only an enum field has values.")
        if (field.type != FieldType.REFERENCE && field.target != null) problem(at, "Only a reference field has a target.")
        if (field.type != FieldType.FILE && field.bucket != null) problem(at, "Only a file field has a bucket.")
        for (rule in field.rules) checkRule(field, rule, at, problem)
    }

    private fun checkRule(field: Field, rule: Rule, at: String, problem: (String, String) -> Unit) {
        val numeric = field.type == FieldType.INT || field.type == FieldType.LONG || field.type == FieldType.DECIMAL
        val text = field.type == FieldType.STRING
        when (rule) {
            is Rule.Min, is Rule.Max -> {
                val bound = if (rule is Rule.Min) rule.value else (rule as Rule.Max).value
                val name = if (rule is Rule.Min) "min" else "max"
                if (!numeric) problem(at, "The $name rule applies to int, long and decimal fields only.")
                else if (Value.Decimal.parseOrNull(bound) == null) problem(at, "The $name bound '$bound' is not a decimal number.")
            }
            is Rule.Length -> when {
                !text -> problem(at, "The length rule applies to string fields only.")
                rule.min == null && rule.max == null -> problem(at, "The length rule needs a min, a max or both.")
                (rule.min ?: 0) < 0 || (rule.max ?: 0) < 0 -> problem(at, "Length bounds can't be negative.")
                rule.min != null && rule.max != null && rule.min > rule.max -> problem(at, "The length min is greater than its max.")
            }
            is Rule.Pattern -> when {
                !text -> problem(at, "The pattern rule applies to string fields only.")
                runCatching { Regex(rule.regex) }.isFailure -> problem(at, "The pattern '${rule.regex}' is not a valid regular expression.")
            }
            is Rule.Custom -> if (rule.name.isBlank()) problem(at, "A custom rule needs a validator name.")
        }
    }

    private fun checkRelation(schema: Schema, resource: Resource, relation: Relation, at: String, problem: (String, String) -> Unit) {
        val target = schema.resource(relation.target)
        if (target == null) {
            problem(at, "The target resource '${relation.target}' doesn't exist.")
            return
        }
        when (relation) {
            is Relation.BelongsTo -> {
                val field = resource.field(relation.field)
                if (field?.type != FieldType.REFERENCE || field.target != target.name) {
                    problem(at, "'${relation.field}' must be a reference field on '${resource.name}' that targets '${target.name}'.")
                }
            }
            is Relation.HasMany -> {
                val field = target.field(relation.foreignField)
                if (field?.type != FieldType.REFERENCE || field.target != resource.name) {
                    problem(at, "'${relation.foreignField}' must be a reference field on '${target.name}' that targets '${resource.name}'.")
                }
            }
        }
    }

    private fun duplicates(names: List<String>): Set<String> =
        names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
}
