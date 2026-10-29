package io.github.basekt.payment.paymongo

import io.github.basekt.payment.CreatePaymentRequest
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
import io.github.ronjunevaldoz.paymongo.IPayMongo
import io.github.ronjunevaldoz.paymongo.PayMongo
import io.github.ronjunevaldoz.paymongo.exception.PayMongoException
import io.github.ronjunevaldoz.paymongo.models.Address
import io.github.ronjunevaldoz.paymongo.models.Amount
import io.github.ronjunevaldoz.paymongo.models.Billing
import io.github.ronjunevaldoz.paymongo.models.resource.CheckoutSession
import io.github.ronjunevaldoz.paymongo.models.resource.CheckoutSessionInput
import io.github.ronjunevaldoz.paymongo.models.resource.CheckoutSessionV2Input
import io.github.ronjunevaldoz.paymongo.models.resource.CreateCheckoutSessionInput
import io.github.ronjunevaldoz.paymongo.models.resource.CreateCheckoutSessionV2Input
import io.github.ronjunevaldoz.paymongo.models.resource.CreatePaymentLinkInput
import io.github.ronjunevaldoz.paymongo.models.resource.CreateRefundInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.time.Instant

internal const val REFERENCE_METADATA_KEY: String = "basekt_reference_id"

/**
 * PayMongo [PaymentProvider] backed by `paymongo-kotlin`. Supports PHP checkout sessions (v2 and v1), payment links,
 * constant-time `Paymongo-Signature` webhook verification, and payment-link refunds.
 *
 * @param settings PayMongo adapter settings.
 * @param client underlying [IPayMongo] SDK client.
 */
public class PayMongoPaymentProvider(
    private val settings: PayMongoSettings,
    private val client: IPayMongo,
) : PaymentProvider, WebhookVerifiable, Refundable {

    /** Creates a [PayMongoPaymentProvider] configured with [settings]. */
    public constructor(settings: PayMongoSettings) : this(
        settings = settings,
        client = PayMongo(PayMongo.Config(secretKey = settings.secretKey)),
    )

    override val id: String get() = settings.id

    override val supportedCurrencies: Set<String> = setOf("PHP")

    private val webhookJson = Json { ignoreUnknownKeys = true }

    // Bounded cache so v2 checkout sessions (which PayMongo creates with deferred PaymentIntent) can still be read back immediately.
    private val recentSessions = ConcurrentHashMap<String, ProviderSession>()

    override suspend fun createSession(request: CreatePaymentRequest): ProviderSession {
        if (request.amount.currency != "PHP" || request.amount.decimals != 2) {
            throw PaymentException(
                "PayMongo supports PHP with 2 decimal places, got ${request.amount.currency} (decimals=${request.amount.decimals}).",
            )
        }
        val centavos = request.amount.toCentavos()
        val metadata = request.metadata + (REFERENCE_METADATA_KEY to request.referenceId)
        val summary = request.description?.takeIf { it.isNotBlank() } ?: "Payment ${request.referenceId}"

        val session = try {
            when (settings.flow) {
                PayMongoFlow.CHECKOUT_SESSION_V2 -> createV2Checkout(request, centavos, summary, metadata)
                PayMongoFlow.CHECKOUT_SESSION_V1 -> createV1Checkout(request, centavos, summary, metadata)
                PayMongoFlow.PAYMENT_LINK -> createLink(request, centavos, summary, metadata)
            }
        } catch (e: PayMongoException) {
            val detail = e.errors.joinToString("; ") { "${it.code}: ${it.detail}" }
            throw PaymentException("PayMongo rejected session creation: $detail", e)
        } catch (e: PaymentException) {
            throw e
        } catch (e: Exception) {
            throw PaymentException("Failed to reach PayMongo: ${e.message}", e)
        }

        if (recentSessions.size > 4_096) recentSessions.clear()
        recentSessions[session.externalId] = session
        return session
    }

    override suspend fun getSession(externalId: String): ProviderSession? {
        if (externalId.isBlank()) return null
        return try {
            when {
                externalId.startsWith("link_") -> {
                    val link = client.getPaymentLink(externalId).data
                    ProviderSession(
                        externalId = link.id,
                        status = mapPayMongoStatus(link.status),
                        amount = PaymentAmount.ofMinorUnits(link.amount.minorUnits.toLong(), "PHP", 2),
                        checkoutUrl = link.url,
                        metadata = link.metadata.orEmpty(),
                    )
                }
                externalId.startsWith("cs_") -> {
                    try {
                        val cs = client.getCheckoutSession(externalId).data
                        val totalCentavos = cs.attributes.lineItems.sumOf { it.amount.minorUnits.toLong() * it.quantity }
                        val firstPaymentId = cs.attributes.payments.firstOrNull()?.id
                        ProviderSession(
                            externalId = cs.id,
                            status = mapPayMongoStatus(cs.attributes.status.value),
                            amount = PaymentAmount.ofMinorUnits(totalCentavos, "PHP", 2),
                            checkoutUrl = cs.attributes.checkoutUrl,
                            paymentReference = firstPaymentId,
                            metadata = cs.attributes.metadata.orEmpty(),
                        )
                    } catch (e: Exception) {
                        // v2 checkout sessions may not match v1's nested schema until settled; fall back to cached session if known.
                        recentSessions[externalId] ?: if (e.isNotFound()) null else throw e
                    }
                }
                else -> recentSessions[externalId]
            }
        } catch (e: PayMongoException) {
            if (e.isNotFound()) null else throw PaymentException("PayMongo lookup failed for '$externalId': ${e.errors.joinToString { it.detail }}", e)
        } catch (e: Exception) {
            recentSessions[externalId] ?: throw PaymentException("Failed to fetch PayMongo session '$externalId': ${e.message}", e)
        }
    }

    override suspend fun verifyWebhook(payload: WebhookPayload): VerifiedPaymentEvent {
        val secret = settings.webhookSecretKey?.takeIf { it.isNotBlank() }
            ?: throw WebhookSignatureException("PayMongo webhookSecretKey is not configured for provider '$id'.")
        val headerValue = payload.headers.entries.firstOrNull { it.key.equals(settings.signatureHeaderName, ignoreCase = true) }?.value
            ?: throw WebhookSignatureException("Missing '${settings.signatureHeaderName}' header.")

        verifySignatureHeader(payload.rawBody, headerValue, secret)
        return decodeWebhookEnvelope(payload.rawBody)
    }

    override suspend fun refund(request: RefundRequest): RefundReceipt {
        val centavos = request.amount.toCentavos()
        val paymentId = request.paymentReference?.takeIf { it.isNotBlank() }
            ?: throw PaymentException("PayMongo refund requires paymentReference (the 'pay_...' id recorded at settlement).")
        if (!request.externalSessionId.startsWith("link_")) {
            throw PaymentException(
                "PayMongo's Payment Link refund endpoint requires a 'link_...' session id (got '${request.externalSessionId}').",
            )
        }
        return try {
            val response = client.createPaymentLinkRefund(
                id = request.externalSessionId,
                input = CreateRefundInput(
                    amount = Amount(centavos),
                    paymentId = paymentId,
                    reason = request.reason,
                ),
            ).data
            recentSessions.computeIfPresent(request.externalSessionId) { _, existing ->
                existing.copy(status = PaymentStatus.REFUNDED)
            }
            RefundReceipt(
                refundId = response.id,
                amount = PaymentAmount.ofMinorUnits(response.amount.minorUnits.toLong(), "PHP", 2),
                status = response.status,
            )
        } catch (e: PayMongoException) {
            val detail = e.errors.joinToString("; ") { "${it.code}: ${it.detail}" }
            throw PaymentException("PayMongo refund failed: $detail", e)
        } catch (e: Exception) {
            throw PaymentException("Failed to call PayMongo refund: ${e.message}", e)
        }
    }

    private suspend fun createV2Checkout(
        request: CreatePaymentRequest,
        centavos: Int,
        summary: String,
        metadata: Map<String, String>,
    ): ProviderSession {
        val lineItem = CheckoutSession.LineItem(
            amount = Amount(centavos),
            currency = "PHP",
            description = summary,
            name = summary,
            quantity = 1,
        )
        val created = client.createCheckoutSessionV2(
            CreateCheckoutSessionV2Input(
                data = CheckoutSessionV2Input(
                    attributes = CheckoutSessionV2Input.AttributesInput(
                        lineItems = listOf(lineItem),
                        paymentMethodTypes = settings.resolvedMethodTypes,
                        description = summary,
                        referenceNumber = request.referenceId,
                        successUrl = request.successUrl ?: settings.defaultSuccessUrl,
                        cancelUrl = request.cancelUrl ?: settings.defaultCancelUrl,
                        statementDescriptor = settings.statementDescriptor,
                        metadata = metadata,
                    ),
                ),
            ),
        ).data
        return ProviderSession(
            externalId = created.id,
            status = PaymentStatus.PENDING,
            amount = request.amount,
            checkoutUrl = created.attributes.checkoutUrl,
            expiresAt = request.expiresAt,
            metadata = metadata,
        )
    }

    private suspend fun createV1Checkout(
        request: CreatePaymentRequest,
        centavos: Int,
        summary: String,
        metadata: Map<String, String>,
    ): ProviderSession {
        val successUrl = request.successUrl ?: settings.defaultSuccessUrl
            ?: throw PaymentException("PayMongo v1 checkout session requires a successUrl (on request or PayMongoSettings.defaultSuccessUrl).")
        val cancelUrl = request.cancelUrl ?: settings.defaultCancelUrl
            ?: throw PaymentException("PayMongo v1 checkout session requires a cancelUrl (on request or PayMongoSettings.defaultCancelUrl).")
        val emptyAddress = Address(city = "", country = "PH", line1 = "", line2 = "", postalCode = "", state = "")
        val emptyBilling = Billing(address = emptyAddress, email = "", name = "", phone = "")
        val lineItem = CheckoutSession.LineItem(
            amount = Amount(centavos),
            currency = "PHP",
            description = summary,
            name = summary,
            quantity = 1,
        )
        val created = client.createCheckoutSession(
            CreateCheckoutSessionInput(
                data = CheckoutSessionInput(
                    attributes = CheckoutSessionInput.AttributesInput(
                        billing = emptyBilling,
                        description = summary,
                        lineItems = listOf(lineItem),
                        paymentMethodTypes = settings.resolvedMethodTypes,
                        referenceNumber = request.referenceId,
                        cancelUrl = cancelUrl,
                        successUrl = successUrl,
                        statementDescriptor = settings.statementDescriptor,
                        metadata = metadata,
                    ),
                ),
            ),
        ).data
        return ProviderSession(
            externalId = created.id,
            status = PaymentStatus.PENDING,
            amount = request.amount,
            checkoutUrl = created.attributes.checkoutUrl,
            expiresAt = request.expiresAt,
            metadata = metadata,
        )
    }

    private suspend fun createLink(
        request: CreatePaymentRequest,
        centavos: Int,
        summary: String,
        metadata: Map<String, String>,
    ): ProviderSession {
        val created = client.createPaymentLink(
            CreatePaymentLinkInput(
                amount = Amount(centavos),
                currency = "PHP",
                description = summary,
                remarks = request.referenceId,
                metadata = metadata,
            ),
        ).data
        return ProviderSession(
            externalId = created.id,
            status = PaymentStatus.PENDING,
            amount = request.amount,
            checkoutUrl = created.url,
            expiresAt = request.expiresAt,
            metadata = metadata,
        )
    }

    private fun verifySignatureHeader(rawBody: String, signatureHeader: String, webhookSecret: String) {
        val fields = signatureHeader.split(',').associate { part ->
            val key = part.substringBefore('=', "").trim()
            val value = part.substringAfter('=', "").trim()
            key to value
        }
        val timestampRaw = fields["t"]?.takeIf { it.isNotEmpty() }
            ?: throw WebhookSignatureException("Malformed '${settings.signatureHeaderName}' header: missing 't=' timestamp.")
        if (!fields.containsKey("te") || !fields.containsKey("li")) {
            throw WebhookSignatureException("Malformed '${settings.signatureHeaderName}' header: expected 't=...,te=...,li=...'.")
        }
        val timestampSeconds = timestampRaw.toLongOrNull()
            ?: throw WebhookSignatureException("Malformed '${settings.signatureHeaderName}' timestamp '$timestampRaw'.")

        if (settings.signatureToleranceSeconds > 0) {
            val nowSeconds = settings.clock.now().epochSeconds
            if (abs(nowSeconds - timestampSeconds) > settings.signatureToleranceSeconds) {
                throw WebhookSignatureException(
                    "PayMongo webhook timestamp ($timestampSeconds) is outside tolerance (${settings.signatureToleranceSeconds}s).",
                )
            }
        }

        val expectedSignature = fields["li"].orEmpty().ifEmpty { fields["te"].orEmpty() }
        if (expectedSignature.isEmpty()) {
            throw WebhookSignatureException("Malformed '${settings.signatureHeaderName}' header: both 'li' and 'te' signatures are empty.")
        }

        val computed = hmacSha256Hex(webhookSecret, "$timestampRaw.$rawBody")
        if (!constantTimeEquals(computed, expectedSignature.lowercase())) {
            throw WebhookSignatureException("PayMongo webhook signature mismatch.")
        }
    }

    private fun decodeWebhookEnvelope(rawBody: String): VerifiedPaymentEvent {
        val root = try {
            webhookJson.parseToJsonElement(rawBody).jsonObject
        } catch (e: Exception) {
            throw WebhookSignatureException("Malformed PayMongo webhook JSON payload.", e)
        }
        val eventObj = root["data"]?.jsonObject
            ?: throw WebhookSignatureException("PayMongo webhook JSON has no 'data' object.")
        val eventId = eventObj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw WebhookSignatureException("PayMongo webhook JSON has no 'data.id'.")
        val eventAttrs = eventObj["attributes"]?.jsonObject
            ?: throw WebhookSignatureException("PayMongo webhook JSON has no 'data.attributes'.")
        val eventType = eventAttrs["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val occurredAt = eventAttrs["created_at"]?.jsonPrimitive?.longOrNull?.let(Instant::fromEpochSeconds)

        val resourceObj = eventAttrs["data"]?.jsonObject
        val resourceId = resourceObj?.get("id")?.jsonPrimitive?.contentOrNull
        val resourceType = resourceObj?.get("type")?.jsonPrimitive?.contentOrNull
        val resourceAttrs = resourceObj?.get("attributes")?.jsonObject ?: resourceObj

        val metadata = resourceAttrs?.get("metadata")?.let { runCatching { it.jsonObject }.getOrNull() }
        val metadataRef = metadata?.get(REFERENCE_METADATA_KEY)?.jsonPrimitive?.contentOrNull
            ?: resourceAttrs?.get("reference_number")?.jsonPrimitive?.contentOrNull
            ?: resourceAttrs?.get("external_reference_number")?.jsonPrimitive?.contentOrNull
            ?: resourceAttrs?.get("remarks")?.jsonPrimitive?.contentOrNull

        val nestedPayments = resourceAttrs?.get("payments")?.let { runCatching { it.jsonArray }.getOrNull() }
        val firstNestedPaymentId = nestedPayments?.firstOrNull()?.let { runCatching { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
        val paymentReference = when (resourceType) {
            "payment" -> resourceId
            else -> firstNestedPaymentId ?: resourceAttrs?.get("payment_id")?.jsonPrimitive?.contentOrNull
        }

        val amountCentavos = resourceAttrs?.get("amount")?.jsonPrimitive?.intOrNull
            ?: resourceAttrs?.get("line_items")?.let { runCatching { it.jsonArray }.getOrNull() }?.sumOf { item ->
                val obj = item.jsonObject
                val unit = obj["amount"]?.jsonPrimitive?.intOrNull ?: 0
                val qty = obj["quantity"]?.jsonPrimitive?.intOrNull ?: 1
                unit * qty
            }?.takeIf { it > 0 }

        val status = when (eventType) {
            "checkout_session.payment.paid", "link.payment.paid", "payment.paid" -> PaymentStatus.SUCCEEDED
            "payment.failed" -> PaymentStatus.FAILED
            "payment.refunded", "payment.refund.updated" -> PaymentStatus.REFUNDED
            "qrph.expired" -> PaymentStatus.EXPIRED
            else -> null
        }

        if (resourceId != null && status != null) {
            recentSessions.computeIfPresent(resourceId) { _, existing ->
                existing.copy(status = status, paymentReference = paymentReference ?: existing.paymentReference)
            }
        }

        return VerifiedPaymentEvent(
            eventKey = eventId,
            status = status,
            externalSessionId = if (resourceType == "payment") null else resourceId,
            referenceId = metadataRef,
            paymentReference = paymentReference,
            amount = amountCentavos?.let { PaymentAmount.ofMinorUnits(it.toLong(), "PHP", 2) },
            occurredAt = occurredAt,
        )
    }

    private fun PaymentAmount.toCentavos(): Int {
        val raw = rawUnits()
        if (raw > BigInteger.valueOf(Int.MAX_VALUE.toLong())) {
            throw PaymentException("PHP amount ${toDecimalString()} exceeds PayMongo's integer centavo limit.")
        }
        return raw.toInt()
    }

    private fun mapPayMongoStatus(raw: String): PaymentStatus = when (raw.lowercase()) {
        "paid", "succeeded" -> PaymentStatus.SUCCEEDED
        "failed", "cancelled" -> PaymentStatus.FAILED
        "expired", "archived" -> PaymentStatus.EXPIRED
        "refunded" -> PaymentStatus.REFUNDED
        else -> PaymentStatus.PENDING
    }

    private fun Throwable.isNotFound(): Boolean =
        this is PayMongoException && errors.any { it.code.equals("resource_not_found", ignoreCase = true) }

    private fun hmacSha256Hex(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.encodeToByteArray(), "HmacSHA256"))
        return mac.doFinal(data.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }
}
