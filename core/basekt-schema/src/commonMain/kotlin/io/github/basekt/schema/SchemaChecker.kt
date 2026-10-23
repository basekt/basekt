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
            val versions = resource.fields.filter { it.auto == Auto.Version }
            if (versions.size > 1) problem("$at.fields", "A resource has at most one version field, not ${versions.joinToString { it.name }}.")
            duplicates(resource.actions.map { it.name }).forEach { problem("$at.actions.$it", "Action '$it' is declared twice.") }
            for (action in resource.actions) checkAction(schema, action, "$at.actions.${action.name}", ::problem)
            checkWorkflow(schema, resource, "$at.workflow", ::problem)
            resource.permissions.scopes.forEachIndexed { i, scope -> checkScope(resource, scope, "$at.permissions.scopes.$i", ::problem) }
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
        if (field.unique && field.auto != null && field.auto !is Auto.Sequence) {
            problem(at, "Only a sequence can be an automatic unique field.")
        }
        for (rule in field.rules) checkRule(field, rule, at, problem)
        field.auto?.let { checkAuto(field, it, at, problem) }
    }

    private fun checkAuto(field: Field, auto: Auto, at: String, problem: (String, String) -> Unit) {
        when (auto) {
            Auto.CreatedAt, Auto.UpdatedAt -> if (field.type != FieldType.INSTANT) {
                problem(at, "A creation or update time needs an instant field.")
            }
            Auto.State -> if (field.type != FieldType.ENUM) problem(at, "A workflow's state needs an enum field.")
            Auto.Version -> if (field.type != FieldType.INT && field.type != FieldType.LONG) {
                problem(at, "A version needs an int or long field.")
            }
            Auto.CreatedBy, Auto.UpdatedBy -> if (field.type != FieldType.STRING && field.type != FieldType.REFERENCE) {
                problem(at, "A caller id needs a string or reference field.")
            }
            is Auto.Sequence -> {
                if (field.type != FieldType.STRING && field.type != FieldType.INT && field.type != FieldType.LONG) {
                    problem(at, "A sequence needs a string, int or long field.")
                }
                if (field.type != FieldType.STRING && (auto.prefix.isNotEmpty() || auto.digits != 0)) {
                    problem(at, "Only a string sequence has a prefix or digits.")
                }
                if (auto.digits !in 0..18) problem(at, "A sequence's digits must be between 0 and 18, not ${auto.digits}.")
                if (auto.start < 0) problem(at, "A sequence can't start below 0.")
            }
        }
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

    private fun checkWorkflow(schema: Schema, resource: Resource, at: String, problem: (String, String) -> Unit) {
        val states = resource.fields.filter { it.auto == Auto.State }
        val workflow = resource.workflow
        if (workflow == null) {
            for (field in states) problem("${at.removeSuffix(".workflow")}.fields.${field.name}", "A state field needs a workflow.")
            return
        }
        val field = resource.field(workflow.field)
        if (field == null || field.type != FieldType.ENUM) {
            problem(at, "The workflow's field '${workflow.field}' must be an enum field of '${resource.name}'.")
            return
        }
        if (field.auto != Auto.State) problem(at, "The workflow's field '${workflow.field}' must be its state, not set otherwise.")
        val values = field.values.orEmpty()
        if (workflow.initial !in values) problem(at, "The initial state '${workflow.initial}' isn't one of ${values.joinToString()}.")
        duplicates(workflow.transitions.map { it.name }).forEach { problem("$at.transitions.$it", "Transition '$it' is declared twice.") }
        for (transition in workflow.transitions) {
            val path = "$at.transitions.${transition.name}"
            checkAction(schema, transition.asAction(), path, problem)
            if (resource.action(transition.name) != null) problem(path, "'${transition.name}' is also an action's name.")
            if (transition.from.isEmpty()) problem(path, "A transition needs at least one state to start from.")
            (transition.from + transition.to).filter { it !in values }.forEach { problem(path, "'$it' isn't one of ${values.joinToString()}.") }
        }
    }

    private fun checkAction(schema: Schema, action: Action, at: String, problem: (String, String) -> Unit) {
        if (!namePattern.matches(action.name)) problem(at, "'${action.name}' is not a valid name: use letters, digits and '_', starting with a letter.")
        if (action.confirm != null && action.confirm.isBlank()) problem(at, "A confirmation question can't be blank.")
        duplicates(action.input.map { it.name }).forEach { problem("$at.input.$it", "Input field '$it' is declared twice.") }
        for (field in action.input) {
            checkField(schema, field, "$at.input.${field.name}", problem)
            if (field.auto != null) problem("$at.input.${field.name}", "An action's input is the caller's, so the server can't set it.")
        }
    }

    private fun checkScope(resource: Resource, scope: Scope, at: String, problem: (String, String) -> Unit) {
        for (clause in scope.clauses) {
            if (clause.field != Resource.ID) {
                val field = resource.field(clause.field)
                when {
                    field == null -> problem(at, "The scoped field '${clause.field}' doesn't exist on '${resource.name}'.")
                    field.type != FieldType.STRING && field.type != FieldType.ENUM && field.type != FieldType.REFERENCE ->
                        problem(at, "The scoped field '${clause.field}' must be a string, enum or reference field.")
                }
            }
            val match = clause.match
            if (match is ScopeMatch.Attribute && match.name.isBlank()) problem(at, "A scope needs an attribute name.")
        }
        if (scope.operations.isEmpty()) problem(at, "A scope needs at least one operation.")
        if (scope.unless.any { it.isBlank() }) problem(at, "A scope's unless roles can't be blank.")
    }

    private fun duplicates(names: List<String>): Set<String> =
        names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
}
