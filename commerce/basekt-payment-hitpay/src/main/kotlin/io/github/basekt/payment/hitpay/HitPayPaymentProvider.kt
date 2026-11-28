package io.github.basekt.payment.hitpay

import io.github.basekt.payment.CreatePaymentRequest
import io.github.basekt.payment.CurrencyDecimals
import io.github.basekt.payment.PaymentAmount
import io.github.basekt.payment.PaymentException
import io.github.basekt.payment.PaymentProvider
import io.github.basekt.payment.PaymentStatus
import io.github.basekt.payment.ProviderSession
import io.github.basekt.payment.RefundReceipt
import io.github.basekt.payment.RefundRequest
import io.github.basekt.payment.Refundable
import io.github.basekt.payment.VerifiedPaymentEvent
import io.github.basekt.payment.WebhookPayload
import io.github.basekt.payment.WebhookSignatureException
import io.github.basekt.payment.WebhookVerifiable
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.math.BigDecimal
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock
import kotlin.time.Instant

private const val API_KEY_HEADER = "X-BUSINESS-API-KEY"
private const val SIGNATURE_HEADER = "Hitpay-Signature"
private const val EVENT_OBJECT_HEADER = "Hitpay-Event-Object"
private const val EVENT_TYPE_HEADER = "Hitpay-Event-Type"

/** HitPay's ids are UUIDs; anything else is never sent into a request path. */
private val HITPAY_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

/** The metadata keys that go to the payment request's own buyer fields instead of its `metadata`. */
private val BUYER_FIELDS = mapOf("email" to "email", "name" to "name", "phone" to "phone")

/** A payment's statuses once the buyer has paid. */
private val PAID_PAYMENT_STATUSES = setOf("succeeded", "succeeded_manually", "refunded", "partially_refunded")

/**
 * HitPay [PaymentProvider] over Ktor [HttpClient], for HitPay's rails in the Philippines (GCash, QRPh, GrabPay,
 * ShopeePay, BillEase, cards...), Singapore (PayNow, cards...) and Malaysia.
 *
 * - Creates hosted payment requests (`POST /v1/payment-requests`) carrying the caller's `referenceId` in
 *   `reference_number`, and reads them back (`GET /v1/payment-requests/{id}`).
 * - Refunds a paid request (`POST /v1/refund`), finding the payment's id from the request when the ledger has none.
 * - Verifies the event webhooks registered in HitPay's dashboard: `Hitpay-Signature` is the hex HMAC-SHA256 of the raw
 *   body under that webhook's salt, compared in constant time. `payment_request` events (`completed`, `failed`) and
 *   `charge` events settle sessions; other objects (payouts, invoices, orders, transfers) are informational.
 *
 * The `webhook` field of a payment request is deprecated by HitPay, so it is not sent and its form-encoded `hmac`
 * callbacks are not verified: register `/payments/webhooks/{id}` as a webhook in the dashboard instead.
 */
public class HitPayPaymentProvider private constructor(
    private val settings: HitPaySettings,
    private val httpClient: HttpClient,
    private val ownsHttpClient: Boolean,
    private val clock: Clock,
) : PaymentProvider, WebhookVerifiable, Refundable, AutoCloseable {

    /**
     * Creates a [HitPayPaymentProvider] sharing an application-managed Ktor [httpClient]. Closing this provider leaves
     * [httpClient] open. [clock] turns `CreatePaymentRequest.expiresAt` into HitPay's `expires_after`.
     */
    public constructor(settings: HitPaySettings, httpClient: HttpClient, clock: Clock = Clock.System) : this(
        settings = settings,
        httpClient = httpClient,
        ownsHttpClient = false,
        clock = clock,
    )

    /** Creates a [HitPayPaymentProvider] with a dedicated CIO [HttpClient] configured with [HitPaySettings.requestTimeout]. */
    public constructor(settings: HitPaySettings) : this(
        settings = settings,
        httpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = settings.requestTimeout.inWholeMilliseconds
                connectTimeoutMillis = settings.requestTimeout.inWholeMilliseconds
            }
        },
        ownsHttpClient = true,
        clock = Clock.System,
    )

    override val id: String get() = settings.id

    override val supportedCurrencies: Set<String> get() = settings.normalizedCurrencies

    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = settings.apiBaseUrl.trimEnd('/')

    override suspend fun createSession(request: CreatePaymentRequest): ProviderSession {
        val currency = request.amount.currency
        requireSupported(currency)
        val redirectUrl = (request.successUrl ?: settings.defaultSuccessUrl)?.trim()?.takeIf { it.isNotEmpty() }
        val extraMetadata = request.metadata.filterKeys { it !in BUYER_FIELDS }
        val body = buildJsonObject {
            put("amount", BigDecimal(request.amount.toDecimalString(fixedScale = true)))
            put("currency", currency)
            put("reference_number", request.referenceId)
            request.description?.trim()?.takeIf { it.isNotEmpty() }?.let { put("purpose", it.take(255)) }
            redirectUrl?.let { put("redirect_url", it) }
            for ((key, field) in BUYER_FIELDS) {
                request.metadata[key]?.trim()?.takeIf { it.isNotEmpty() }?.let { put(field, it) }
            }
            if (settings.paymentMethods.isNotEmpty()) {
                putJsonArray("payment_methods") { settings.paymentMethods.forEach { add(JsonPrimitive(it)) } }
            }
            request.expiresAt?.let { put("expires_after", expiresAfter(it)) }
            put("send_email", settings.sendEmail.toString())
            put("allow_repeated_payments", "false")
            if (extraMetadata.isNotEmpty()) {
                putJsonObject("metadata") { extraMetadata.forEach { (k, v) -> put(k, v.take(500)) } }
            }
        }.toString()

        val response = send("POST /payment-requests") {
            post("$baseUrl/payment-requests") {
                withHeaders()
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw PaymentException("HitPay payment request failed with HTTP ${response.status.value}: ${apiError(text)}")
        }
        val created = parseObject(text, "payment request")
        val externalId = created.text("id")?.takeIf { it.isNotBlank() }
            ?: throw PaymentException("HitPay payment request response has no 'id'.")
        val checkoutUrl = created.text("url")?.takeIf { it.startsWith("https://") }
            ?: throw PaymentException("HitPay payment request response has a missing or non-HTTPS 'url'.")
        return ProviderSession(
            externalId = externalId,
            status = requestStatus(created.text("status"), settledPayment(created)) ?: PaymentStatus.PENDING,
            amount = request.amount,
            checkoutUrl = checkoutUrl,
            expiresAt = request.expiresAt,
            metadata = request.metadata + ("referenceId" to request.referenceId),
        )
    }

    override suspend fun getSession(externalId: String): ProviderSession? {
        if (!HITPAY_ID.matches(externalId)) return null
        val response = send("GET /payment-requests/$externalId") {
            get("$baseUrl/payment-requests/$externalId") { withHeaders() }
        }
        if (response.status == HttpStatusCode.NotFound) return null
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw PaymentException("HitPay payment request lookup failed with HTTP ${response.status.value}: ${apiError(text)}")
        }
        val found = parseObject(text, "payment request lookup")
        val payment = settledPayment(found)
        val amount = amountOf(found)
            ?: throw PaymentException("HitPay payment request '$externalId' has no readable 'amount' and 'currency'.")
        val metadata = buildMap {
            found.text("reference_number")?.let { put("referenceId", it) }
            found.text("status")?.let { put("providerStatus", it) }
            payment?.text("payment_type")?.let { put("paymentType", it) }
        }
        return ProviderSession(
            externalId = found.text("id") ?: externalId,
            status = requestStatus(found.text("status"), payment) ?: PaymentStatus.PENDING,
            amount = amount,
            checkoutUrl = found.text("url"),
            paymentReference = payment?.text("id"),
            metadata = metadata,
        )
    }

    override suspend fun refund(request: RefundRequest): RefundReceipt {
        requireSupported(request.amount.currency)
        val paymentId = request.paymentReference?.takeIf { it.isNotBlank() }
            ?: getSession(request.externalSessionId)?.paymentReference
            ?: throw PaymentException("HitPay payment request '${request.externalSessionId}' has no paid payment to refund.")
        if (!HITPAY_ID.matches(paymentId)) throw PaymentException("'$paymentId' is not a HitPay payment id.")
        val body = buildJsonObject {
            put("payment_id", paymentId)
            put("amount", BigDecimal(request.amount.toDecimalString(fixedScale = true)))
        }.toString()
        val response = send("POST /refund") {
            post("$baseUrl/refund") {
                withHeaders()
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw PaymentException("HitPay refund failed with HTTP ${response.status.value}: ${apiError(text)}")
        }
        val refund = parseObject(text, "refund")
        val refundId = refund.text("id")?.takeIf { it.isNotBlank() }
            ?: throw PaymentException("HitPay refund response has no 'id'.")
        val refunded = refund.text("amount_refunded")?.let { raw ->
            val currency = refund.text("currency")?.uppercase() ?: request.amount.currency
            runCatching { PaymentAmount.ofDecimal(raw, currency, request.amount.decimals) }.getOrNull()
        }
        return RefundReceipt(
            refundId = refundId,
            amount = refunded ?: request.amount,
            status = refund.text("status") ?: "succeeded",
        )
    }

    override suspend fun verifyWebhook(payload: WebhookPayload): VerifiedPaymentEvent {
        val salt = settings.webhookSalt
            ?: throw WebhookSignatureException("HitPay webhookSalt is not configured for provider '$id'.")
        val signature = payload.header(SIGNATURE_HEADER)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: throw WebhookSignatureException("Missing '$SIGNATURE_HEADER' header.")
        val expected = hmacSha256Hex(salt, payload.rawBody)
        if (!MessageDigest.isEqual(expected.encodeToByteArray(), signature.encodeToByteArray())) {
            throw WebhookSignatureException("HitPay webhook signature mismatch.")
        }

        val root = try {
            json.parseToJsonElement(payload.rawBody) as? JsonObject
        } catch (e: Exception) {
            throw WebhookSignatureException("Malformed HitPay webhook JSON payload.", e)
        } ?: throw WebhookSignatureException("HitPay webhook payload is not a JSON object.")
        val objectId = root.text("id")?.takeIf { it.isNotBlank() }
            ?: throw WebhookSignatureException("HitPay webhook payload has no 'id'.")
        val eventObject = payload.header(EVENT_OBJECT_HEADER)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: when {
                "payments" in root || "reference_number" in root -> "payment_request"
                "payment_request_id" in root -> "charge"
                else -> "unknown"
            }
        val eventType = payload.header(EVENT_TYPE_HEADER)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val providerStatus = root.text("status")?.lowercase()
        val eventKey = "$eventObject:$objectId:${providerStatus.orEmpty()}:${root.text("updated_at").orEmpty()}"

        val metadata = LinkedHashMap<String, String>()
        metadata["eventObject"] = eventObject
        eventType?.let { metadata["eventType"] = it }
        providerStatus?.let { metadata["providerStatus"] = it }

        return when (eventObject) {
            "payment_request" -> {
                val payment = settledPayment(root)
                payment?.text("payment_type")?.let { metadata["paymentType"] = it }
                payment?.text("fees")?.let { metadata["fees"] = it }
                VerifiedPaymentEvent(
                    eventKey = eventKey,
                    status = requestStatus(providerStatus, payment),
                    externalSessionId = objectId,
                    referenceId = root.text("reference_number"),
                    paymentReference = payment?.text("id"),
                    amount = amountOf(root),
                    occurredAt = instantOf(root.text("updated_at")),
                    metadata = metadata,
                )
            }
            "charge" -> {
                root.text("payment_type")?.let { metadata["paymentType"] = it }
                VerifiedPaymentEvent(
                    eventKey = eventKey,
                    status = chargeStatus(providerStatus),
                    externalSessionId = root.text("payment_request_id"),
                    referenceId = root.text("reference_number"),
                    paymentReference = objectId,
                    amount = amountOf(root),
                    occurredAt = instantOf(root.text("updated_at")),
                    metadata = metadata,
                )
            }
            else -> VerifiedPaymentEvent(
                eventKey = "ignored:$eventKey",
                status = null,
                metadata = metadata + ("ignoredReason" to "unsupported_object"),
            )
        }
    }

    /** Closes the internal [HttpClient] if created by this provider; no-op when a shared [HttpClient] was supplied. */
    override fun close() {
        if (ownsHttpClient) httpClient.close()
    }

    private fun requireSupported(currency: String) {
        if (currency !in supportedCurrencies) {
            throw PaymentException(
                "HitPay provider '$id' does not support currency '$currency' (supported: ${supportedCurrencies.joinToString()}).",
            )
        }
    }

    /** HitPay counts a request's lifetime from its creation, in whole minutes, rounded up. */
    private fun expiresAfter(expiresAt: Instant): String {
        val seconds = (expiresAt - clock.now()).inWholeSeconds
        if (seconds <= 0) throw PaymentException("The payment's expiresAt ($expiresAt) has already passed.")
        return "${(seconds + 59) / 60} mins"
    }

    /** The request's status, refunded when its payment was refunded in full; `null` while it is still open. */
    private fun requestStatus(status: String?, payment: JsonObject?): PaymentStatus? = when (status?.lowercase()) {
        "completed" -> if (payment?.text("status") == "refunded") PaymentStatus.REFUNDED else PaymentStatus.SUCCEEDED
        "failed", "canceled", "cancelled", "inactive" -> PaymentStatus.FAILED
        "expired" -> PaymentStatus.EXPIRED
        else -> null
    }

    private fun chargeStatus(status: String?): PaymentStatus? = when (status) {
        "succeeded", "succeeded_manually" -> PaymentStatus.SUCCEEDED
        "refunded" -> PaymentStatus.REFUNDED
        "failed" -> PaymentStatus.FAILED
        else -> null
    }

    /** The payment that paid the request, if any. */
    private fun settledPayment(request: JsonObject): JsonObject? =
        (request["payments"] as? JsonArray).orEmpty()
            .filterIsInstance<JsonObject>()
            .firstOrNull { it.text("status") in PAID_PAYMENT_STATUSES }

    private fun amountOf(obj: JsonObject): PaymentAmount? {
        val raw = obj.text("amount") ?: return null
        val currency = obj.text("currency")?.trim()?.uppercase() ?: return null
        val decimals = CurrencyDecimals.forCurrency(currency) ?: return null
        return runCatching { PaymentAmount.ofDecimal(raw, currency, decimals) }.getOrNull()
    }

    /** HitPay's timestamps often carry no offset; those are left out rather than guessed. */
    private fun instantOf(raw: String?): Instant? = raw?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private suspend fun send(label: String, call: suspend HttpClient.() -> HttpResponse): HttpResponse =
        try {
            httpClient.call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PaymentException("HitPay '$label' transport error: ${e.message}", e)
        }

    private fun HttpRequestBuilder.withHeaders() {
        header(API_KEY_HEADER, settings.apiKey)
        header("X-Requested-With", "XMLHttpRequest")
        accept(ContentType.Application.Json)
        if (httpClient.pluginOrNull(HttpTimeout) != null) {
            timeout { requestTimeoutMillis = settings.requestTimeout.inWholeMilliseconds }
        }
    }

    private fun parseObject(raw: String, context: String): JsonObject =
        try {
            json.parseToJsonElement(raw) as? JsonObject
        } catch (e: Exception) {
            throw PaymentException("HitPay $context returned invalid JSON.", e)
        } ?: throw PaymentException("HitPay $context did not return a JSON object.")

    /** HitPay's errors are `{"message": ..., "errors": {field: [...]}}`. */
    private fun apiError(raw: String): String = runCatching {
        val obj = json.parseToJsonElement(raw) as JsonObject
        listOfNotNull(obj.text("message"), obj["errors"]?.toString()).joinToString(" ").ifEmpty { raw.take(200) }
    }.getOrDefault(raw.take(200))

    private fun hmacSha256Hex(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.encodeToByteArray(), "HmacSHA256"))
        return mac.doFinal(payload.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** A string or number field as text, or `null` when absent, `null`, or not a primitive. */
private fun JsonObject.text(key: String): String? =
    (this[key]?.takeUnless { it is JsonNull } as? JsonPrimitive)?.contentOrNull

private fun WebhookPayload.header(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
