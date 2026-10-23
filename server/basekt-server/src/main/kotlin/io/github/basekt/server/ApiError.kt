package io.github.basekt.server

import io.github.basekt.schema.FieldError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The JSON body of every error BaseKt returns to a client. */
@Serializable
public data class ApiError(
    /** What kind of error it is. */
    val error: ErrorCode,
    /** What went wrong, in a sentence. */
    val message: String,
    /** The rejected fields, for [ErrorCode.VALIDATION_FAILED]. */
    val fields: List<FieldError> = emptyList(),
)

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

    /** The record's id is already taken. */
    @SerialName("conflict") CONFLICT(409),

    /** At least one field was rejected; see [ApiError.fields]. */
    @SerialName("validation_failed") VALIDATION_FAILED(422),
}

/** Thrown by [ResourceService] to answer a request with [error]. */
public class ApiException(
    /** The error to send. */
    public val error: ApiError,
) : RuntimeException(error.message) {
    /** Creates an exception for an error with no field details. */
    public constructor(code: ErrorCode, message: String) : this(ApiError(code, message))
}
