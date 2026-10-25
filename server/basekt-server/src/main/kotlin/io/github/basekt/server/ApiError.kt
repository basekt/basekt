package io.github.basekt.server

import io.github.basekt.schema.FieldError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The JSON body of every error BaseKt returns to a client. */
@Serializable
public data class ApiError(
    /** What kind of error it is. */
    val error: ErrorCode,
    /** What went wrong, in a sentence. */
    val message: String,
    /** The rejected fields, for [ErrorCode.VALIDATION_FAILED]. */
    val fields: List<FieldError> = emptyList(),
    /** The id of the call that failed, which the server's logs name too. See [requestId]. */
    val requestId: String? = null,
)

private val ErrorJson = Json { encodeDefaults = true }

/** Encodes this error as the JSON body BaseKt sends. */
public fun ApiError.toJson(): String = ErrorJson.encodeToString(ApiError.serializer(), this)

/** The kinds of [ApiError], each with its HTTP status. */
@Serializable
public enum class ErrorCode(
    /** The HTTP status code it is sent with. */
    public val status: Int,
) {
    /** The request body isn't a JSON object. */
    @SerialName("bad_request") BAD_REQUEST(400),

    /** A filter, sort, limit or cursor parameter is invalid. */
    @SerialName("invalid_query") INVALID_QUERY(400),

    /** The caller isn't authenticated and the operation needs a caller. */
    @SerialName("unauthorized") UNAUTHORIZED(401),

    /** The caller's roles don't allow the operation. */
    @SerialName("forbidden") FORBIDDEN(403),

    /** The resource, record or relation doesn't exist. */
    @SerialName("not_found") NOT_FOUND(404),

    /** The resource is read-only, as a provided one is: its records can't be created, updated or deleted. */
    @SerialName("read_only") READ_ONLY(405),

    /** The record's id is already taken. */
    @SerialName("conflict") CONFLICT(409),

    /** An upload is bigger than its limit. */
    @SerialName("payload_too_large") PAYLOAD_TOO_LARGE(413),

    /** An upload's content isn't a type its destination accepts. */
    @SerialName("unsupported_type") UNSUPPORTED_TYPE(415),

    /** At least one field was rejected; see [ApiError.fields]. */
    @SerialName("validation_failed") VALIDATION_FAILED(422),

    /** A hook of the application refused the write; see [ApiError.message]. */
    @SerialName("rejected") REJECTED(422),

    /** The data source can't do what the request needs, such as computing aggregates. */
    @SerialName("not_implemented") NOT_IMPLEMENTED(501),

    /** Something failed on the server. The server logged it with [ApiError.requestId]. */
    @SerialName("internal_error") INTERNAL_ERROR(500),

    /**
     * Something the server depends on, such as a lookup of the caller's roles or the provider of a provided
     * resource, failed. Try again later.
     */
    @SerialName("unavailable") UNAVAILABLE(503),
}

/**
 * Thrown by [ResourceService] to answer a request with [error]. A [cause] is logged with server errors and never
 * sent to the client.
 */
public class ApiException @JvmOverloads constructor(
    /** The error to send. */
    public val error: ApiError,
    cause: Throwable? = null,
) : RuntimeException(error.message, cause) {
    /** Creates an exception for an error with no field details, caused by [cause] if it has one. */
    @JvmOverloads
    public constructor(code: ErrorCode, message: String, cause: Throwable? = null) : this(ApiError(code, message), cause)
}
