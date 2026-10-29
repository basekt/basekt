package io.github.basekt.payments

import io.github.basekt.payment.WebhookPayload
import io.github.basekt.server.ApiException
import io.github.basekt.server.ErrorCode
import io.github.basekt.server.IdentityResolver
import io.github.basekt.server.requestId
import io.github.basekt.server.toJson
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseUrlEncodedParameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
private data class CreateSessionBody(
    val provider: String,
    val successUrl: String? = null,
    val cancelUrl: String? = null,
    val payerAddress: String? = null,
)

@Serializable
private data class ConfirmOnChainBody(
    val txHash: String,
)

@Serializable
private data class RefundBody(
    val reason: String = "requested_by_customer",
)

private val WireJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

internal fun Route.paymentRoutes(
    runtime: PaymentsRuntime,
    identities: IdentityResolver,
    clientIp: (ApplicationCall) -> String?,
) {
    route(runtime.path) {
        get("providers") {
            respondJson(HttpStatusCode.OK) {
                WireJson.encodeToString(ListSerializer(ProviderSummary.serializer()), runtime.providers())
            }
        }

        post("{resource}/{recordId}") {
            respondJson(HttpStatusCode.Created) {
                val body = parseBody(CreateSessionBody.serializer(), call.receiveText())
                val caller = identities.resolve(call)
                val created = runtime.createSession(
                    resourceName = call.parameters["resource"]!!,
                    recordId = call.parameters["recordId"]!!,
                    providerId = body.provider,
                    caller = caller,
                    successUrl = body.successUrl,
                    cancelUrl = body.cancelUrl,
                    payerAddress = body.payerAddress,
                )
                WireJson.encodeToString(PaymentSessionView.serializer(), created)
            }
        }

        get("sessions/{id}") {
            respondJson(HttpStatusCode.OK) {
                val caller = identities.resolve(call)
                val session = runtime.getSession(call.parameters["id"]!!, caller)
                WireJson.encodeToString(PaymentSessionView.serializer(), session)
            }
        }

        post("sessions/{id}/confirm") {
            respondJson(HttpStatusCode.OK) {
                val body = parseBody(ConfirmOnChainBody.serializer(), call.receiveText())
                val caller = identities.resolve(call)
                val confirmed = runtime.confirmOnChain(call.parameters["id"]!!, body.txHash, caller)
                WireJson.encodeToString(PaymentSessionView.serializer(), confirmed)
            }
        }

        post("sessions/{id}/refund") {
            respondJson(HttpStatusCode.OK) {
                val raw = call.receiveText()
                val body = if (raw.isBlank()) RefundBody() else parseBody(RefundBody.serializer(), raw)
                val caller = identities.resolve(call)
                val refunded = runtime.refund(call.parameters["id"]!!, body.reason, caller)
                WireJson.encodeToString(PaymentSessionView.serializer(), refunded)
            }
        }

        get("webhooks/{provider}") {
            handleIncomingWebhook(runtime, clientIp)
        }

        post("webhooks/{provider}") {
            handleIncomingWebhook(runtime, clientIp)
        }
    }
}

private suspend fun RoutingContext.handleIncomingWebhook(
    runtime: PaymentsRuntime,
    clientIp: (ApplicationCall) -> String?,
) {
    try {
        val providerId = call.parameters["provider"]!!
        val rawBody = if (call.request.httpMethod == HttpMethod.Get) "" else call.receiveText()
        val headers = call.request.headers.entries().associate { (k, values) -> k to values.firstOrNull().orEmpty() }
        val queryParameters = call.request.queryParameters.entries().associate { (k, values) -> k to values.firstOrNull().orEmpty() }
        val isFormBody = rawBody.isNotEmpty() &&
            runCatching { call.request.contentType().withoutParameters() == ContentType.Application.FormUrlEncoded }.getOrDefault(false)
        val formParameters = if (isFormBody) {
            rawBody.parseUrlEncodedParameters().entries().associate { (k, values) -> k to values.firstOrNull().orEmpty() }
        } else {
            emptyMap()
        }
        val payload = WebhookPayload(
            rawBody = rawBody,
            headers = headers,
            queryParameters = queryParameters,
            formParameters = formParameters,
            remoteIp = clientIp(call),
        )
        val outcome = runtime.handleWebhook(providerId, payload)
        val plainAck = runtime.plainTextWebhookAck(providerId)
        if (plainAck != null) {
            call.respondText(plainAck, ContentType.Text.Plain, HttpStatusCode.OK)
        } else {
            call.respondText(
                WireJson.encodeToString(WebhookResult.serializer(), outcome),
                ContentType.Application.Json,
                HttpStatusCode.OK,
            )
        }
    } catch (e: ApiException) {
        val err = e.error.copy(requestId = call.requestId)
        call.respondText(
            err.toJson(),
            ContentType.Application.Json,
            HttpStatusCode.fromValue(err.error.status),
        )
    }
}

private fun <T> parseBody(serializer: kotlinx.serialization.KSerializer<T>, raw: String): T = try {
    WireJson.decodeFromString(serializer, raw)
} catch (e: Exception) {
    throw ApiException(ErrorCode.BAD_REQUEST, "Malformed JSON request body.", e)
}

private suspend fun RoutingContext.respondJson(status: HttpStatusCode, block: suspend () -> String) {
    try {
        call.respondText(block(), ContentType.Application.Json, status)
    } catch (e: ApiException) {
        val err = e.error.copy(requestId = call.requestId)
        call.respondText(
            err.toJson(),
            ContentType.Application.Json,
            HttpStatusCode.fromValue(err.error.status),
        )
    }
}
