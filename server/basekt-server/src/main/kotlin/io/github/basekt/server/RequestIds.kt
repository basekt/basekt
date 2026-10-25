package io.github.basekt.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import kotlinx.coroutines.currentCoroutineContext
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

private val RequestIdKey = AttributeKey<String>("BaseKtRequestId")

/** An id a proxy may pass on: letters, digits, `.`, `_`, `:` and `-`, up to 128 of them. */
private val passedOn = Regex("[A-Za-z0-9._:-]{1,128}")

/**
 * The id of this call: the one a proxy sent in the request id header, when it is a plain token, or a new random one.
 * BaseKt sends it back in the same header and in every error body, and logs it with every server error, so a client's
 * report finds the server's log line. `null` when [BaseKt] isn't installed.
 */
public val ApplicationCall.requestId: String?
    get() = attributes.getOrNull(RequestIdKey)

/** The call a coroutine works for, so code with no Ktor types, such as [ResourceService], can name its [requestId]. */
internal class CallElement(val call: ApplicationCall) : AbstractCoroutineContextElement(CallElement) {
    companion object Key : CoroutineContext.Key<CallElement>
}

/** The [requestId] of the call this coroutine works for, or `null` outside a call. */
internal suspend fun currentRequestId(): String? = currentCoroutineContext()[CallElement]?.call?.requestId

/** Gives this call its [requestId] and sends it back in [header]. */
internal fun ApplicationCall.assignRequestId(header: String) {
    val id = request.header(header)?.takeIf { passedOn.matches(it) } ?: UUID.randomUUID().toString()
    attributes.put(RequestIdKey, id)
    response.header(header, id)
}

/** This error with [call]'s [requestId], unless it names one already. */
public fun ApiError.forCall(call: ApplicationCall): ApiError = if (requestId != null) this else copy(requestId = call.requestId)

/** Answers this call with [error] as JSON, carrying its [requestId], with [status] (the error's own by default). */
public suspend fun ApplicationCall.respondError(error: ApiError, status: HttpStatusCode = HttpStatusCode.fromValue(error.error.status)) {
    respondText(error.forCall(this).toJson(), ContentType.Application.Json, status)
}

/**
 * Runs [block] and answers what it throws: an [ApiException] with its error, a malformed request with
 * [ErrorCode.BAD_REQUEST], and anything else with [ErrorCode.INTERNAL_ERROR], whose message names the [requestId]
 * and nothing of the cause. Server errors are logged with the request id, the method and the path, never the body or
 * credentials. Once a response has started, a failure can only be logged. Cancellation passes through.
 */
public suspend fun ApplicationCall.answeringErrors(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        if (e.error.error.status >= 500) application.log.warn("BaseKt request $requestId: ${describe()} answered ${e.error.error.name.lowercase()}: ${e.message}", e.cause)
        if (!response.isCommitted) respondError(e.error)
    } catch (e: BadRequestException) {
        if (!response.isCommitted) respondError(ApiError(ErrorCode.BAD_REQUEST, "The request is malformed."))
    } catch (e: Exception) {
        application.log.error("BaseKt request $requestId failed: ${describe()}", e)
        if (!response.isCommitted) {
            respondError(ApiError(ErrorCode.INTERNAL_ERROR, "Something went wrong on the server. Quote request id $requestId to report it."))
        }
    }
}

private fun ApplicationCall.describe(): String = "${request.httpMethod.value} ${request.path()}"
