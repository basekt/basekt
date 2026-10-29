package io.github.basekt.payment.lemonsqueezy

import io.github.basekt.payment.CreatePaymentRequest
import io.github.basekt.payment.CurrencyDecimals
import io.github.basekt.payment.PaymentAmount
import io.github.basekt.payment.PaymentException
import io.github.basekt.payment.PaymentProvider
import io.github.basekt.payment.PaymentStatus
import io.github.basekt.payment.ProviderSession
import io.github.basekt.payment.VerifiedPaymentEvent
import io.github.basekt.payment.WebhookPayload
import io.github.basekt.payment.WebhookSignatureException
import io.github.basekt.payment.WebhookVerifiable
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.math.BigInteger
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Instant

private val JsonApiContentType: ContentType = ContentType("application", "vnd.api+json")

/**
 * Lemon Squeezy Merchant-of-Record [PaymentProvider] over Ktor [HttpClient].
 *
 * - Creates hosted JSON:API checkouts (`POST /v1/checkouts`) carrying `reference_id` in `checkout_data.custom` (and
 *   optional `custom_price` in minor units).
 * - Fetches existing checkouts (`GET /v1/checkouts/{id}`) and subscription customer-portal URLs
 *   ([customerPortalUrl], `GET /v1/subscriptions/{id}`).
 * - Verifies incoming webhooks (`X-Signature` hex HMAC-SHA256) in constant time and enforces `store_id` and `test_mode`
 *   isolation across order and subscription events.
 */
public class LemonSqueezyPaymentProvider private constructor(
    private val settings: LemonSqueezySettings,
    private val httpClient: HttpClient,
    private val ownsHttpClient: Boolean,
) : PaymentProvider, WebhookVerifiable, AutoCloseable {

    /**
     * Creates a [LemonSqueezyPaymentProvider] sharing an application-managed Ktor [httpClient]. Closing this provider
     * leaves [httpClient] open.
     */
    public constructor(settings: LemonSqueezySettings, httpClient: HttpClient) : this(
        settings = settings,
        httpClient = httpClient,
        ownsHttpClient = false,
    )

    /**
     * Creates a [LemonSqueezyPaymentProvider] with a dedicated CIO [HttpClient] configured with
     * [LemonSqueezySettings.requestTimeout].
     */
    public constructor(settings: LemonSqueezySettings) : this(
        settings = settings,
        httpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = settings.requestTimeout.inWholeMilliseconds
                connectTimeoutMillis = settings.requestTimeout.inWholeMilliseconds
            }
        },
        ownsHttpClient = true,
    )

    override val id: String get() = settings.id

    override val supportedCurrencies: Set<String> get() = settings.normalizedCurrencies

    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = settings.apiBaseUrl.trimEnd('/')
    private val recentSessions = ConcurrentHashMap<String, ProviderSession>()

    override suspend fun createSession(request: CreatePaymentRequest): ProviderSession {
        val currency = request.amount.currency
        if (currency !in supportedCurrencies) {
            throw PaymentException(
                "Lemon Squeezy provider '$id' does not support currency '$currency' (supported: ${supportedCurrencies.joinToString()}).",
            )
        }
        val variantId = request.metadata["variantId"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: settings.normalizedVariants[currency]
            ?: settings.defaultVariantId?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw PaymentException(
                "Lemon Squeezy provider '$id' has no variantId configured for currency '$currency' (set defaultVariantId, variantsByCurrency, or metadata[\"variantId\"]).",
            )

        val customMetadata = LinkedHashMap<String, JsonElement>()
        for ((k, v) in request.metadata) {
            if (k != "email" && k != "variantId") {
                customMetadata[k] = JsonPrimitive(v)
            }
        }
        customMetadata["reference_id"] = JsonPrimitive(request.referenceId)

        val checkoutData = LinkedHashMap<String, JsonElement>()
        request.metadata["email"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            checkoutData["email"] = JsonPrimitive(it)
        }
        checkoutData["custom"] = JsonObject(customMetadata)

        val attributes = LinkedHashMap<String, JsonElement>()
        if (settings.sendCustomPrice) {
            val rawUnits = request.amount.rawUnits()
            if (rawUnits > BigInteger.valueOf(Int.MAX_VALUE.toLong())) {
                throw PaymentException("Amount ${request.amount.toDecimalString()} $currency exceeds Lemon Squeezy's integer custom_price limit.")
            }
            attributes["custom_price"] = JsonPrimitive(rawUnits.toInt())
        }
        attributes["test_mode"] = JsonPrimitive(settings.testMode)
        attributes["checkout_data"] = JsonObject(checkoutData)

        val redirectUrl = (request.successUrl ?: settings.defaultSuccessUrl)?.trim()?.takeIf { it.isNotEmpty() }
        val summary = request.description?.trim()?.takeIf { it.isNotEmpty() }
        if (redirectUrl != null || summary != null) {
            val productOptions = LinkedHashMap<String, JsonElement>()
            summary?.let {
                productOptions["name"] = JsonPrimitive(it)
                productOptions["description"] = JsonPrimitive(it)
            }
            redirectUrl?.let { productOptions["redirect_url"] = JsonPrimitive(it) }
            attributes["product_options"] = JsonObject(productOptions)
        }
        request.expiresAt?.let { attributes["expires_at"] = JsonPrimitive(it.toString()) }

        val requestPayload = JsonObject(
            mapOf(
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("checkouts"),
                        "attributes" to JsonObject(attributes),
                        "relationships" to JsonObject(
                            mapOf(
                                "store" to JsonObject(
                                    mapOf(
                                        "data" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("stores"),
                                                "id" to JsonPrimitive(settings.storeId),
                                            ),
                                        ),
                                    ),
                                ),
                                "variant" to JsonObject(
                                    mapOf(
                                        "data" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("variants"),
                                                "id" to JsonPrimitive(variantId),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ).toString()

        val response = executeRequest("POST /checkouts") {
            post("$baseUrl/checkouts") {
                header(HttpHeaders.Authorization, "Bearer ${settings.apiKey}")
                accept(JsonApiContentType)
                contentType(JsonApiContentType)
                applyRequestTimeout()
                setBody(requestPayload)
            }
        }
        val bodyText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw PaymentException("Lemon Squeezy checkout creation failed with HTTP ${response.status.value}: ${extractApiError(bodyText)}")
        }

        val dataObj = parseDataObject(bodyText, "checkout creation")
        val externalId = dataObj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw PaymentException("Lemon Squeezy checkout response is missing 'data.id'.")
        val dataAttrs = dataObj["attributes"]?.jsonObject
            ?: throw PaymentException("Lemon Squeezy checkout response is missing 'data.attributes'.")
        val checkoutUrl = dataAttrs["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("https://") }
            ?: throw PaymentException("Lemon Squeezy checkout response returned a missing or non-HTTPS 'data.attributes.url'.")
        val expiresAt = dataAttrs["expires_at"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: request.expiresAt

        val session = ProviderSession(
            externalId = externalId,
            status = PaymentStatus.PENDING,
            amount = request.amount,
            checkoutUrl = checkoutUrl,
            expiresAt = expiresAt,
            metadata = request.metadata + mapOf(
                "referenceId" to request.referenceId,
                "variantId" to variantId,
            ),
        )
        if (recentSessions.size > 4_096) recentSessions.clear()
        recentSessions[externalId] = session
        return session
    }

    override suspend fun getSession(externalId: String): ProviderSession? {
        if (externalId.isBlank()) return null
        val response = executeRequest("GET /checkouts/$externalId") {
            get("$baseUrl/checkouts/$externalId") {
                header(HttpHeaders.Authorization, "Bearer ${settings.apiKey}")
                accept(JsonApiContentType)
                applyRequestTimeout()
            }
        }
        if (response.status == HttpStatusCode.NotFound) return null
        val bodyText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw PaymentException("Lemon Squeezy checkout lookup failed with HTTP ${response.status.value}: ${extractApiError(bodyText)}")
        }
        val dataObj = parseDataObject(bodyText, "checkout lookup")
        val id = dataObj["id"]?.jsonPrimitive?.contentOrNull ?: externalId
        val attrs = dataObj["attributes"]?.jsonObject
        val url = attrs?.get("url")?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
        val expiresAt = attrs?.get("expires_at")?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val cached = recentSessions[id]
        val resolvedAmount = cached?.amount ?: run {
            val defaultCurrency = supportedCurrencies.first()
            val decimals = CurrencyDecimals.forCurrency(defaultCurrency) ?: 2
            val minor = attrs?.get("custom_price")?.takeUnless { it is JsonNull }?.jsonPrimitive?.longOrNull ?: 0L
            PaymentAmount.ofMinorUnits(minor, defaultCurrency, decimals)
        }
        return ProviderSession(
            externalId = id,
            status = cached?.status ?: PaymentStatus.PENDING,
            amount = resolvedAmount,
            checkoutUrl = url ?: cached?.checkoutUrl,
            expiresAt = expiresAt ?: cached?.expiresAt,
            paymentReference = cached?.paymentReference,
            metadata = cached?.metadata.orEmpty(),
        )
    }

    /**
     * Fetches the hosted billing customer-portal URL for a Lemon Squeezy [subscriptionId]
     * (`GET /v1/subscriptions/{id}` -> `data.attributes.urls.customer_portal`), or returns `null` when not found.
     */
    public suspend fun customerPortalUrl(subscriptionId: String): String? {
        val trimmed = subscriptionId.trim()
        require(trimmed.isNotEmpty()) { "subscriptionId must not be blank." }
        val response = executeRequest("GET /subscriptions/$trimmed") {
            get("$baseUrl/subscriptions/$trimmed") {
                header(HttpHeaders.Authorization, "Bearer ${settings.apiKey}")
                accept(JsonApiContentType)
                applyRequestTimeout()
            }
        }
        if (response.status == HttpStatusCode.NotFound) return null
        val bodyText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw PaymentException(
                "Lemon Squeezy subscription lookup failed with HTTP ${response.status.value}: ${extractApiError(bodyText)}",
            )
        }
        val dataObj = parseDataObject(bodyText, "subscription lookup")
        val urlsObj = dataObj["attributes"]?.jsonObject?.get("urls")?.takeUnless { it is JsonNull }?.jsonObject
        return urlsObj?.get("customer_portal")?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.startsWith("https://") }
    }

    override suspend fun verifyWebhook(payload: WebhookPayload): VerifiedPaymentEvent {
        val secret = settings.webhookSecret?.takeIf { it.isNotBlank() }
            ?: throw WebhookSignatureException("Lemon Squeezy webhookSecret is not configured for provider '$id'.")
        val signatureHeader = payload.headers.entries
            .firstOrNull { it.key.equals(settings.signatureHeaderName, ignoreCase = true) }
            ?.value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: throw WebhookSignatureException("Missing '${settings.signatureHeaderName}' header.")

        val computed = hmacSha256Hex(secret, payload.rawBody)
        if (!MessageDigest.isEqual(computed.encodeToByteArray(), signatureHeader.encodeToByteArray())) {
            throw WebhookSignatureException("Lemon Squeezy webhook signature mismatch.")
        }

        val root = try {
            json.parseToJsonElement(payload.rawBody).jsonObject
        } catch (e: Exception) {
            throw WebhookSignatureException("Malformed Lemon Squeezy webhook JSON payload.", e)
        }

        val meta = root["meta"]?.jsonObject
            ?: throw WebhookSignatureException("Lemon Squeezy webhook JSON has no 'meta' object.")
        val eventName = meta["event_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw WebhookSignatureException("Lemon Squeezy webhook JSON has no 'meta.event_name'.")
        val customDataObj = meta["custom_data"]?.takeUnless { it is JsonNull }?.let { runCatching { it.jsonObject }.getOrNull() }

        val dataObj = root["data"]?.jsonObject
            ?: throw WebhookSignatureException("Lemon Squeezy webhook JSON has no 'data' object.")
        val resourceId = dataObj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw WebhookSignatureException("Lemon Squeezy webhook JSON has no 'data.id'.")
        val resourceType = dataObj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val attrs = dataObj["attributes"]?.jsonObject
            ?: throw WebhookSignatureException("Lemon Squeezy webhook JSON has no 'data.attributes'.")

        val storeId = attrs["store_id"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
        if (storeId != null && storeId != settings.storeId) {
            return VerifiedPaymentEvent(
                eventKey = "ignored-store:$eventName:$resourceId",
                status = null,
                metadata = mapOf("eventName" to eventName, "ignoredReason" to "store_mismatch"),
            )
        }

        val eventTestMode = attrs["test_mode"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.booleanOrNull
        if (eventTestMode != null && eventTestMode != settings.testMode) {
            return VerifiedPaymentEvent(
                eventKey = "ignored-mode:$eventName:$resourceId",
                status = null,
                metadata = mapOf("eventName" to eventName, "ignoredReason" to "mode_mismatch"),
            )
        }

        val attrStatus = attrs["status"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull?.lowercase()
        val refundedFlag = attrs["refunded"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.booleanOrNull == true
        val status = mapEventToStatus(eventName, attrStatus, refundedFlag)

        val customPairs = LinkedHashMap<String, String>()
        customDataObj?.forEach { (k, v) ->
            v.takeUnless { it is JsonNull }?.let { elem ->
                runCatching { elem.jsonPrimitive.contentOrNull }.getOrNull()?.let { customPairs[k] = it }
            }
        }

        val referenceId = customPairs["reference_id"] ?: customPairs["basekt_reference_id"]
        val updatedAt = attrs["updated_at"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
        val createdAt = attrs["created_at"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
        val occurredAt = (updatedAt ?: createdAt)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val eventKey = "$eventName:$resourceId:${updatedAt ?: createdAt.orEmpty()}"

        val currency = attrs["currency"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull?.uppercase()
        val totalMinor = attrs["total"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.longOrNull
        val amount = if (currency != null && totalMinor != null && totalMinor >= 0L) {
            CurrencyDecimals.forCurrency(currency)?.let { scale ->
                PaymentAmount.ofMinorUnits(totalMinor, currency, scale)
            }
        } else {
            null
        }

        val firstOrderItem = attrs["first_order_item"]?.takeUnless { it is JsonNull }?.let { runCatching { it.jsonObject }.getOrNull() }
        val variantId = attrs["variant_id"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
            ?: firstOrderItem?.get("variant_id")?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
        val customerId = attrs["customer_id"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
        val renewsAt = attrs["renews_at"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
        val endsAt = attrs["ends_at"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
        val portalUrl = attrs["urls"]?.takeUnless { it is JsonNull }
            ?.let { runCatching { it.jsonObject["customer_portal"]?.takeUnless { c -> c is JsonNull }?.jsonPrimitive?.contentOrNull }.getOrNull() }

        val metadata = LinkedHashMap<String, String>(customPairs)
        metadata["eventName"] = eventName
        if (resourceType.isNotEmpty()) metadata["resourceType"] = resourceType
        metadata["resourceId"] = resourceId
        attrStatus?.let { metadata["providerStatus"] = it }
        variantId?.let { metadata["variantId"] = it }
        customerId?.let { metadata["customerId"] = it }
        renewsAt?.let { metadata["renewsAt"] = it }
        endsAt?.let { metadata["endsAt"] = it }
        portalUrl?.let { metadata["customerPortalUrl"] = it }

        val paymentReference = when (resourceType) {
            "subscription-invoices" -> attrs["subscription_id"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull ?: resourceId
            else -> resourceId
        }

        return VerifiedPaymentEvent(
            eventKey = eventKey,
            status = status,
            referenceId = referenceId,
            paymentReference = paymentReference,
            amount = amount,
            occurredAt = occurredAt,
            metadata = metadata,
        )
    }

    /** Closes the internal [HttpClient] if created by this provider; no-op when a shared [HttpClient] was supplied. */
    override fun close() {
        if (ownsHttpClient) {
            httpClient.close()
        }
    }

    private fun mapEventToStatus(
        eventName: String,
        attrStatus: String?,
        refundedFlag: Boolean,
    ): PaymentStatus? = when (eventName) {
        "order_created" -> when {
            refundedFlag || attrStatus == "refunded" -> PaymentStatus.REFUNDED
            attrStatus == null || attrStatus == "paid" -> PaymentStatus.SUCCEEDED
            attrStatus == "failed" -> PaymentStatus.FAILED
            else -> null
        }
        "order_refunded" -> PaymentStatus.REFUNDED
        "subscription_created", "subscription_updated" -> when (attrStatus) {
            "active", "on_trial" -> PaymentStatus.SUCCEEDED
            "past_due", "unpaid" -> PaymentStatus.FAILED
            "expired" -> PaymentStatus.EXPIRED
            else -> null
        }
        "subscription_resumed",
        "subscription_unpaused",
        "subscription_payment_success",
        "subscription_payment_recovered",
        -> PaymentStatus.SUCCEEDED
        "subscription_payment_failed" -> PaymentStatus.FAILED
        "subscription_payment_refunded" -> if (refundedFlag || attrStatus == "refunded" || attrStatus == null) PaymentStatus.REFUNDED else null
        "subscription_expired" -> PaymentStatus.EXPIRED
        else -> null
    }

    private suspend fun executeRequest(label: String, call: suspend HttpClient.() -> HttpResponse): HttpResponse =
        try {
            httpClient.call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PaymentException("Lemon Squeezy '$label' transport error: ${e.message}", e)
        }

    private fun io.ktor.client.request.HttpRequestBuilder.applyRequestTimeout() {
        if (httpClient.pluginOrNull(HttpTimeout) != null) {
            timeout {
                requestTimeoutMillis = settings.requestTimeout.inWholeMilliseconds
            }
        }
    }

    private fun parseDataObject(rawJson: String, context: String): JsonObject {
        val root = try {
            json.parseToJsonElement(rawJson).jsonObject
        } catch (e: Exception) {
            throw PaymentException("Lemon Squeezy $context returned invalid JSON.", e)
        }
        return root["data"]?.jsonObject
            ?: throw PaymentException("Lemon Squeezy $context JSON has no 'data' object.")
    }

    private fun extractApiError(rawJson: String): String = runCatching {
        val errors = json.parseToJsonElement(rawJson).jsonObject["errors"]
        errors?.toString() ?: rawJson.take(200)
    }.getOrDefault(rawJson.take(200))

    private fun hmacSha256Hex(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.encodeToByteArray(), "HmacSHA256"))
        return mac.doFinal(payload.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
