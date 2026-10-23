package io.github.basekt.schema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant

/** Why a field's value was rejected. */
@Serializable
public enum class FieldErrorCode {
    /** A required field is missing or null. */
    @SerialName("required") REQUIRED,

    /** The value doesn't have the field's type. */
    @SerialName("type") TYPE,

    /** The value is below a [Rule.Min] bound. */
    @SerialName("min") MIN,

    /** The value is above a [Rule.Max] bound. */
    @SerialName("max") MAX,

    /** The text's length is outside a [Rule.Length] range. */
    @SerialName("length") LENGTH,

    /** The text doesn't match a [Rule.Pattern]. */
    @SerialName("pattern") PATTERN,

    /** The value isn't one of an enum field's values. */
    @SerialName("enum") ENUM,

    /** A [Rule.Custom] validator rejected the value. */
    @SerialName("custom") CUSTOM,

    /** The resource has no field of that name. */
    @SerialName("unknown_field") UNKNOWN_FIELD,

    /** The field can't be written by this operation, such as the id on an update. */
    @SerialName("read_only") READ_ONLY,

    /** A reference points to a record that doesn't exist. */
    @SerialName("missing_reference") MISSING_REFERENCE,

    /** A hook of the application rejected the value. */
    @SerialName("rejected") REJECTED,

    /** Another record already holds the value of a [Field.unique] field. */
    @SerialName("unique") UNIQUE,
}

/** A rejected value of [field], with a typed [code] and a readable [message]. */
@Serializable
public data class FieldError(
    /** The field's name. */
    val field: String,
    /** Why it was rejected. */
    val code: FieldErrorCode,
    /** What is wrong, in a sentence. */
    val message: String,
)

/** Whether a record is written whole or patched. */
public enum class WriteMode {
    /** A new record: required fields must be present, and an id may be given. */
    CREATE,

    /** A patch of an existing record: only the given fields are checked, and the id can't change. */
    UPDATE,
}

/** The outcome of [decodeRecord]: a typed [Record] or the errors that prevented one. */
public sealed interface DecodeResult {
    /** The JSON decoded and passed every check. */
    public data class Valid(
        /** The decoded record, holding only the keys the JSON had. */
        val record: Record,
    ) : DecodeResult

    /** The JSON had at least one bad field. */
    public data class Invalid(
        /** Every bad field, in field order. */
        val errors: List<FieldError>,
    ) : DecodeResult
}

/**
 * Decodes [json] into a record of this resource and checks it: types, required fields, enum values, [Rule]s and
 * [Rule.Custom] [validators]. In [WriteMode.UPDATE], only the fields present are checked.
 */
public fun Resource.decodeRecord(
    json: JsonObject,
    mode: WriteMode,
    validators: Map<String, FieldValidator> = emptyMap(),
): DecodeResult {
    val errors = mutableListOf<FieldError>()
    val record = linkedMapOf<String, Value?>()
    for ((name, element) in json) {
        if (name == Resource.ID) {
            when {
                mode == WriteMode.UPDATE -> errors += FieldError(name, FieldErrorCode.READ_ONLY, "The id can't be changed.")
                element !is JsonPrimitive || !element.isString || element.content.isEmpty() ->
                    errors += FieldError(name, FieldErrorCode.TYPE, "The id must be a non-empty string.")
                else -> record[name] = Value.Text(element.content)
            }
            continue
        }
        val field = field(name)
        if (field == null) {
            errors += FieldError(name, FieldErrorCode.UNKNOWN_FIELD, "'${this.name}' has no field '$name'.")
            continue
        }
        // An update may name the version it last read; the server compares it instead of writing it.
        val expectedVersion = field.auto == Auto.Version && mode == WriteMode.UPDATE
        if (field.auto != null && !expectedVersion) {
            if (element !is JsonNull) errors += FieldError(name, FieldErrorCode.READ_ONLY, "'$name' is set by the server.")
            continue
        }
        if (element is JsonNull) {
            record[name] = null
            continue
        }
        when (val decoded = field.decode(element)) {
            null -> errors += FieldError(name, FieldErrorCode.TYPE, "Expected ${field.typeDescription()}.")
            else -> record[name] = decoded
        }
    }
    errors += validateRecord(record, mode, validators).filter { error -> errors.none { it.field == error.field } }
    return if (errors.isEmpty()) DecodeResult.Valid(record) else DecodeResult.Invalid(errors)
}

/**
 * Checks an already typed [record] of this resource: required fields (in [WriteMode.CREATE], or any field set to
 * null), enum values, [Rule]s and [Rule.Custom] [validators]. Returns every error, or an empty list.
 */
public fun Resource.validateRecord(
    record: Record,
    mode: WriteMode,
    validators: Map<String, FieldValidator> = emptyMap(),
): List<FieldError> {
    val errors = mutableListOf<FieldError>()
    for (field in fields) {
        val present = field.name in record
        val value = record[field.name]
        if (value == null) {
            // The server sets an automatic field after the client's record is decoded.
            if (field.required && (mode == WriteMode.CREATE || present) && !(field.auto != null && !present)) {
                errors += FieldError(field.name, FieldErrorCode.REQUIRED, "'${field.name}' is required.")
            }
            continue
        }
        field.check(value, validators)?.let { errors += it }
    }
    return errors
}

/** Encodes a record as JSON, with the id first. */
public fun Resource.encodeRecord(record: Record): JsonObject {
    val out = linkedMapOf<String, JsonElement>()
    record[Resource.ID]?.let { out[Resource.ID] = it.toJson() }
    for (field in fields) if (field.name in record) out[field.name] = record[field.name]?.toJson() ?: JsonNull
    return JsonObject(out)
}

/** Encodes this value as JSON. Decimals are strings so that no precision is lost; instants are ISO-8601 strings. */
public fun Value.toJson(): JsonElement = when (this) {
    is Value.Text -> JsonPrimitive(value)
    is Value.Integer -> JsonPrimitive(value)
    is Value.Decimal -> JsonPrimitive(literal)
    is Value.Bool -> JsonPrimitive(value)
    is Value.Timestamp -> JsonPrimitive(value.toString())
}

/**
 * Decodes a JSON value for this field, or returns `null` if it has the wrong type. Checks enum values and integer
 * ranges, not [Rule]s.
 */
public fun Field.decode(json: JsonElement): Value? {
    val primitive = json as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return when (type) {
        FieldType.STRING, FieldType.ENUM, FieldType.REFERENCE, FieldType.FILE -> if (primitive.isString) Value.Text(primitive.content) else null
        FieldType.INT -> primitive.takeUnless { it.isString }?.longOrNull
            ?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.let(Value::Integer)
        FieldType.LONG -> primitive.takeUnless { it.isString }?.longOrNull?.let(Value::Integer)
        FieldType.DECIMAL -> Value.Decimal.parseOrNull(primitive.content)
        FieldType.BOOL -> primitive.takeUnless { it.isString }?.booleanOrNull?.let(Value::Bool)
        FieldType.INSTANT -> if (primitive.isString) parse(primitive.content) else null
    }
}

/**
 * Parses [text], such as a query parameter, as a value of this field, or returns `null` if it can't. Unlike
 * [decode], numbers and booleans come as text here.
 */
public fun Field.parse(text: String): Value? = when (type) {
    FieldType.STRING, FieldType.ENUM, FieldType.REFERENCE, FieldType.FILE -> Value.Text(text)
    FieldType.INT -> text.toIntOrNull()?.let { Value.Integer(it.toLong()) }
    FieldType.LONG -> text.toLongOrNull()?.let(Value::Integer)
    FieldType.DECIMAL -> Value.Decimal.parseOrNull(text)
    FieldType.BOOL -> text.toBooleanStrictOrNull()?.let(Value::Bool)
    FieldType.INSTANT -> runCatching { Instant.parse(text) }.getOrNull()?.let(Value::Timestamp)
}

/** Returns the [Rule.Custom] validator names this schema uses that [validators] don't provide. */
public fun Schema.missingValidators(validators: Map<String, FieldValidator>): Set<String> =
    resources.flatMap { it.fields }.flatMap { it.rules }.filterIsInstance<Rule.Custom>()
        .map { it.name }.filterNot { it in validators }.toSet()

private fun Field.check(value: Value, validators: Map<String, FieldValidator>): FieldError? {
    fun error(code: FieldErrorCode, message: String) = FieldError(name, code, message)
    if (type == FieldType.ENUM && (value as? Value.Text)?.value !in values.orEmpty()) {
        return error(FieldErrorCode.ENUM, "Expected one of ${values.orEmpty().joinToString()}.")
    }
    if (type == FieldType.FILE && !isFileKey((value as? Value.Text)?.value)) {
        return error(FieldErrorCode.TYPE, "Expected ${typeDescription()}.")
    }
    for (rule in rules) {
        when (rule) {
            is Rule.Min -> if (value.asDecimal() < Value.Decimal.of(rule.value)) {
                return error(FieldErrorCode.MIN, "Must be at least ${rule.value}.")
            }
            is Rule.Max -> if (value.asDecimal() > Value.Decimal.of(rule.value)) {
                return error(FieldErrorCode.MAX, "Must be at most ${rule.value}.")
            }
            is Rule.Length -> {
                val length = (value as Value.Text).value.length
                if (rule.min != null && length < rule.min || rule.max != null && length > rule.max) {
                    val range = when {
                        rule.min == null -> "at most ${rule.max}"
                        rule.max == null -> "at least ${rule.min}"
                        else -> "between ${rule.min} and ${rule.max}"
                    }
                    return error(FieldErrorCode.LENGTH, "Must be $range characters long.")
                }
            }
            is Rule.Pattern -> if (!Regex(rule.regex).matches((value as Value.Text).value)) {
                return error(FieldErrorCode.PATTERN, rule.message ?: "Must match ${rule.regex}.")
            }
            is Rule.Custom -> {
                val validator = validators[rule.name]
                    ?: return error(FieldErrorCode.CUSTOM, "No validator is registered as '${rule.name}'.")
                validator.validate(value)?.let { return error(FieldErrorCode.CUSTOM, it) }
            }
        }
    }
    return null
}

private fun Value.asDecimal(): Value.Decimal = when (this) {
    is Value.Decimal -> this
    is Value.Integer -> Value.Decimal.of(value.toString())
    else -> throw IllegalArgumentException("A ${this::class.simpleName} is not a number")
}

private fun Field.typeDescription(): String = when (type) {
    FieldType.STRING -> "a string"
    FieldType.INT -> "a 32-bit integer"
    FieldType.LONG -> "an integer"
    FieldType.DECIMAL -> "a decimal number, as a number or a string"
    FieldType.BOOL -> "true or false"
    FieldType.INSTANT -> "an ISO-8601 instant such as 2026-01-31T12:00:00Z"
    FieldType.ENUM -> "one of ${values.orEmpty().joinToString()}"
    FieldType.REFERENCE -> "the id of a '$target' record"
    FieldType.FILE -> "the key of a file in the '$bucket' bucket, such as $bucket/…/name.jpg"
}

private val fileKeySegment = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

/** Whether [key] is a file key in this field's bucket: its bucket, then at least one more safe segment. */
private fun Field.isFileKey(key: String?): Boolean {
    if (key == null || key.length > 1024) return false
    val segments = key.split('/')
    return segments.size >= 2 && segments[0] == bucket && segments.all(fileKeySegment::matches)
}
