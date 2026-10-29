package io.github.basekt.payments

import io.github.basekt.data.Comparison
import io.github.basekt.data.DataSource
import io.github.basekt.data.DuplicateIdException
import io.github.basekt.data.Filter
import io.github.basekt.data.Query
import io.github.basekt.payment.CreatePaymentRequest
import io.github.basekt.payment.CurrencyDecimals
import io.github.basekt.payment.InsufficientFundsException
import io.github.basekt.payment.OnChainConfirmable
import io.github.basekt.payment.OnChainConfirmation
import io.github.basekt.payment.OnChainTransferInstruction
import io.github.basekt.payment.PaymentAmount
import io.github.basekt.payment.PaymentException
import io.github.basekt.payment.PaymentProvider
import io.github.basekt.payment.PaymentStatus
import io.github.basekt.payment.PaymentVerificationException
import io.github.basekt.payment.PayoutCapable
import io.github.basekt.payment.PayoutReceipt
import io.github.basekt.payment.PayoutRequest
import io.github.basekt.payment.RefundRequest
import io.github.basekt.payment.Refundable
import io.github.basekt.payment.WalletBalance
import io.github.basekt.payment.WebhookForbiddenException
import io.github.basekt.payment.WebhookPayload
import io.github.basekt.payment.WebhookSignatureException
import io.github.basekt.payment.WebhookVerifiable
import io.github.basekt.schema.Access
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import io.github.basekt.server.ApiException
import io.github.basekt.server.ErrorCode
import io.github.basekt.server.Identity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Public description of a registered [PaymentProvider] returned by `GET {path}/providers`.
 */
@Serializable
public data class ProviderSummary(
    public val id: String,
    public val currencies: List<String>,
    public val webhook: Boolean,
    public val onChain: Boolean,
    public val refundable: Boolean,
    public val payout: Boolean,
)

/**
 * Public JSON view of a payment session stored in the `payments` ledger.
 */
@Serializable
public data class PaymentSessionView(
    public val id: String,
    public val provider: String,
    public val externalId: String,
    public val resource: String,
    public val recordId: String,
    public val status: PaymentStatus,
    public val amount: String,
    public val minorUnits: String,
    public val decimals: Int,
    public val currency: String,
    public val checkoutUrl: String? = null,
    public val onChain: OnChainTransferInstruction? = null,
    public val txHash: String? = null,
    public val payer: String? = null,
    public val paymentReference: String? = null,
    public val createdAt: String,
    public val updatedAt: String,
    public val expiresAt: String? = null,
)

/**
 * Delivered to `onSettled` listeners after a payment status transition commits in [DataSource].
 */
public data class PaymentSettlement(
    /** The updated payment session. */
    public val session: PaymentSessionView,
    /** The status before this transition. */
    public val previousStatus: PaymentStatus,
    /** The deduplicated webhook event id or `<chainId>:<txHash>:<logIndex>` transfer key. */
    public val eventKey: String,
)

/**
 * Result returned after verifying and processing an incoming provider webhook.
 */
@Serializable
public data class WebhookResult(
    public val eventKey: String,
    public val duplicate: Boolean,
    public val sessionId: String? = null,
    public val status: PaymentStatus? = null,
)

/**
 * Runtime service exposed at `application.baseKtPayments` for routes, background jobs and application code.
 */
public class PaymentsRuntime internal constructor(
    /** Where the HTTP routes are mounted (such as `"/payments"`). */
    public val path: String,
    private val schema: Schema,
    private val dataSource: DataSource,
    private val ledgerResource: Resource,
    private val receiptsResource: Resource,
    private val providers: Map<String, PaymentProvider>,
    private val currencyDecimals: Map<String, Int>,
    private val sessionTtl: Duration?,
    private val createAccess: Access,
    private val readAccess: Access,
    private val refundAccess: Access,
    private val clock: Clock,
    initialScanBlocks: Map<String, Long>,
    private val settlementListeners: List<suspend (PaymentSettlement) -> Unit>,
    private val warnLogger: (String, Throwable?) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val scanCursors = ConcurrentHashMap<String, Long>(initialScanBlocks)

    /** Lists the capabilities of every registered [PaymentProvider]. */
    public fun providers(): List<ProviderSummary> = providers.values.map { p ->
        ProviderSummary(
            id = p.id,
            currencies = p.supportedCurrencies.sorted(),
            webhook = p is WebhookVerifiable,
            onChain = p is OnChainConfirmable,
            refundable = p is Refundable,
            payout = p is PayoutCapable,
        )
    }

    /**
     * Creates a payment session for [recordId] of [resourceName] using [providerId]. Reads the amount and currency
     * directly from the stored record so clients can never tamper with prices.
     */
    public suspend fun createSession(
        resourceName: String,
        recordId: String,
        providerId: String,
        caller: Identity? = null,
        successUrl: String? = null,
        cancelUrl: String? = null,
        payerAddress: String? = null,
    ): PaymentSessionView {
        enforceAccess(createAccess, caller)
        val resource = schema.resource(resourceName)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "Unknown resource '$resourceName'.")
        val payable = resource.payable
            ?: throw ApiException(ErrorCode.NOT_FOUND, "Resource '$resourceName' is not payable.")
        val provider = providers[providerId]
            ?: throw ApiException(ErrorCode.BAD_REQUEST, "Unknown payment provider '$providerId'.")

        val record = dataSource.get(resource, recordId)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "No '$resourceName' record with id '$recordId'.")

        if (payable.statusField != null && payable.paidValue != null) {
            val currentStatus = (record[payable.statusField] as? Value.Text)?.value
            if (currentStatus == payable.paidValue) {
                throw ApiException(ErrorCode.CONFLICT, "Record '$recordId' of '$resourceName' is already paid.")
            }
        }

        val currency = (payable.fixedCurrency ?: (record[payable.currencyField!!] as? Value.Text)?.value)
            ?.trim()?.uppercase()
            ?: throw ApiException(ErrorCode.VALIDATION_FAILED, "Record '$recordId' has no currency set.")
        if (currency !in provider.supportedCurrencies) {
            throw ApiException(
                ErrorCode.BAD_REQUEST,
                "Provider '$providerId' supports ${provider.supportedCurrencies.joinToString()}, not '$currency'.",
            )
        }

        val decimals = currencyDecimals[currency] ?: CurrencyDecimals.forCurrency(currency)
            ?: throw ApiException(ErrorCode.BAD_REQUEST, "No decimal scale configured for currency '$currency'.")

        val amountLiteral = when (val raw = record[payable.amountField]) {
            is Value.Decimal -> raw.literal
            is Value.Integer -> raw.value.toString()
            else -> throw ApiException(ErrorCode.VALIDATION_FAILED, "Record '$recordId' has no '${payable.amountField}' amount.")
        }
        val paymentAmount = try {
            PaymentAmount.ofDecimal(amountLiteral, currency, decimals)
        } catch (e: IllegalArgumentException) {
            throw ApiException(ErrorCode.VALIDATION_FAILED, e.message ?: "Invalid payment amount.", e)
        }
        if (!paymentAmount.isPositive) {
            throw ApiException(ErrorCode.VALIDATION_FAILED, "Payment amount for '$recordId' must be greater than zero.")
        }

        val description = payable.descriptionField?.let { (record[it] as? Value.Text)?.value }
            ?: "$resourceName/$recordId"
        val buyerEmail = payable.buyerEmailField?.let { (record[it] as? Value.Text)?.value?.takeIf(String::isNotBlank) }
        val resolvedPayer = payerAddress?.takeIf { it.isNotBlank() }
            ?: payable.payerAddressField?.let { (record[it] as? Value.Text)?.value?.takeIf(String::isNotBlank) }

        val now = clock.now()
        val expiresAt = sessionTtl?.let { now + it }
        val paymentId = "pay_" + UUID.randomUUID().toString().replace("-", "")
        val requestMetadata = buildMap {
            put("resource", resourceName)
            put("recordId", recordId)
            buyerEmail?.let { put("email", it) }
        }

        val providerSession = try {
            provider.createSession(
                CreatePaymentRequest(
                    referenceId = paymentId,
                    amount = paymentAmount,
                    description = description,
                    successUrl = successUrl,
                    cancelUrl = cancelUrl,
                    payerAddress = resolvedPayer,
                    expiresAt = expiresAt,
                    metadata = requestMetadata,
                ),
            )
        } catch (e: PaymentException) {
            throw ApiException(ErrorCode.UNAVAILABLE, e.message ?: "Payment provider '$providerId' failed.", e)
        }

        val onChainJson = providerSession.onChain?.let {
            json.encodeToString(OnChainTransferInstruction.serializer(), it)
        }
        val stored = dataSource.create(
            ledgerResource,
            mapOf(
                Resource.ID to Value.Text(paymentId),
                PaymentLedgerFields.PROVIDER to Value.Text(provider.id),
                PaymentLedgerFields.EXTERNAL_ID to Value.Text(providerSession.externalId),
                PaymentLedgerFields.RESOURCE to Value.Text(resourceName),
                PaymentLedgerFields.RECORD_ID to Value.Text(recordId),
                PaymentLedgerFields.AMOUNT to Value.Decimal.of(paymentAmount.toDecimalString()),
                PaymentLedgerFields.MINOR_UNITS to Value.Text(paymentAmount.minorUnits),
                PaymentLedgerFields.DECIMALS to Value.Integer(paymentAmount.decimals.toLong()),
                PaymentLedgerFields.CURRENCY to Value.Text(paymentAmount.currency),
                PaymentLedgerFields.STATUS to Value.Text(providerSession.status.toWire()),
                PaymentLedgerFields.CHECKOUT_URL to providerSession.checkoutUrl?.let(Value::Text),
                PaymentLedgerFields.ON_CHAIN_JSON to onChainJson?.let(Value::Text),
                PaymentLedgerFields.TX_HASH to null,
                PaymentLedgerFields.PAYER to resolvedPayer?.let(Value::Text),
                PaymentLedgerFields.PAYMENT_REFERENCE to providerSession.paymentReference?.let(Value::Text),
                PaymentLedgerFields.ACTOR to caller?.id?.let(Value::Text),
                PaymentLedgerFields.CREATED_AT to Value.Timestamp(now),
                PaymentLedgerFields.UPDATED_AT to Value.Timestamp(now),
                PaymentLedgerFields.EXPIRES_AT to (providerSession.expiresAt ?: expiresAt)?.let(Value::Timestamp),
            ),
        )
        return stored.toSessionView()
    }

    /** Returns the payment session [id], marking a stale pending session as `expired` if its window passed. */
    public suspend fun getSession(id: String, caller: Identity? = null): PaymentSessionView {
        enforceAccess(readAccess, caller)
        val record = dataSource.get(ledgerResource, id)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "No payment session '$id'.")
        enforceSessionOwnershipIfScoped(record, caller)
        val expiredRecord = expireIfPastDeadline(record)
        return expiredRecord.toSessionView()
    }

    /**
     * Confirms an on-chain payment session [id] using [txHash], atomically recording the `<chainId>:<txHash>:<logIndex>`
     * receipt so no transaction log can ever settle two sessions.
     */
    public suspend fun confirmOnChain(id: String, txHash: String, caller: Identity? = null): PaymentSessionView {
        enforceAccess(createAccess, caller)
        val existing = dataSource.get(ledgerResource, id)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "No payment session '$id'.")
        enforceSessionOwnershipIfScoped(existing, caller)

        val currentStatus = paymentStatusFromWire(existing.text(PaymentLedgerFields.STATUS)!!)
        val existingTx = existing.text(PaymentLedgerFields.TX_HASH)
        if (currentStatus == PaymentStatus.SUCCEEDED && existingTx != null && existingTx.equals(txHash.trim(), ignoreCase = true)) {
            return existing.toSessionView()
        }
        val checked = expireIfPastDeadline(existing)
        val checkedStatus = paymentStatusFromWire(checked.text(PaymentLedgerFields.STATUS)!!)
        if (checkedStatus != PaymentStatus.PENDING) {
            throw ApiException(ErrorCode.CONFLICT, "Payment session '$id' is already ${checkedStatus.toWire()}.")
        }

        val providerId = checked.text(PaymentLedgerFields.PROVIDER)!!
        val provider = providers[providerId]
            ?: throw ApiException(ErrorCode.BAD_REQUEST, "Payment provider '$providerId' is not registered.")
        val onChain = provider as? OnChainConfirmable
            ?: throw ApiException(ErrorCode.BAD_REQUEST, "Provider '$providerId' does not use on-chain transaction confirmation.")

        val expectedAmount = checked.paymentAmount()
        val expectedPayer = checked.text(PaymentLedgerFields.PAYER)
        val confirmation = try {
            onChain.confirmTransaction(txHash, expectedAmount, expectedPayer)
        } catch (e: PaymentVerificationException) {
            throw ApiException(ErrorCode.REJECTED, e.message ?: "On-chain transaction verification failed.", e)
        } catch (e: PaymentException) {
            throw ApiException(ErrorCode.UNAVAILABLE, e.message ?: "Failed to query on-chain receipt.", e)
        }

        val settlement = applyOnChainConfirmation(id, provider.id, confirmation)
        if (settlement != null) notifySettled(settlement)
        return settlement?.session ?: dataSource.get(ledgerResource, id)!!.toSessionView()
    }

    internal fun plainTextWebhookAck(providerId: String): String? =
        (providers[providerId] as? WebhookVerifiable)?.plainTextAck

    /**
     * Verifies a signed webhook or pingback for [providerId], deduplicates it in `paymentReceipts`, and transitions the
     * matching payment session and target record in one transaction.
     */
    public suspend fun handleWebhook(
        providerId: String,
        headers: Map<String, String>,
        rawBody: String,
    ): WebhookResult = handleWebhook(providerId, WebhookPayload(rawBody = rawBody, headers = headers))

    /**
     * Verifies a signed webhook or pingback [payload] for [providerId], deduplicates it in `paymentReceipts`, and
     * transitions the matching payment session and target record in one transaction.
     */
    public suspend fun handleWebhook(
        providerId: String,
        payload: WebhookPayload,
    ): WebhookResult {
        val provider = providers[providerId]
            ?: throw ApiException(ErrorCode.NOT_FOUND, "Unknown payment provider '$providerId'.")
        val verifiable = provider as? WebhookVerifiable
            ?: throw ApiException(ErrorCode.NOT_FOUND, "Provider '$providerId' does not accept webhooks.")

        val event = try {
            verifiable.verifyWebhook(payload)
        } catch (e: WebhookForbiddenException) {
            throw ApiException(ErrorCode.FORBIDDEN, e.message ?: "Webhook sender is not allowed.", e)
        } catch (e: WebhookSignatureException) {
            throw ApiException(ErrorCode.BAD_REQUEST, e.message ?: "Invalid webhook signature.", e)
        } catch (e: PaymentException) {
            throw ApiException(ErrorCode.UNAVAILABLE, e.message ?: "Webhook verification failed.", e)
        }

        val targetStatus = event.status
            ?: return WebhookResult(eventKey = event.eventKey, duplicate = false, status = null)

        val receiptId = PaymentReceiptFields.receiptId(provider.id, event.eventKey)
        val outcome = try {
            dataSource.transaction { tx ->
                val existingReceipt = tx.get(receiptsResource, receiptId)
                if (existingReceipt != null) {
                    return@transaction WebhookTxOutcome.Duplicate(
                        sessionId = existingReceipt.text(PaymentReceiptFields.PAYMENT_ID),
                    )
                }

                val sessionRecord = findSessionForWebhook(tx, provider.id, event.referenceId, event.externalSessionId)
                    ?: return@transaction WebhookTxOutcome.Unmatched
                val sessionId = sessionRecord.id()
                val previousStatus = paymentStatusFromWire(sessionRecord.text(PaymentLedgerFields.STATUS)!!)

                tx.create(
                    receiptsResource,
                    mapOf(
                        Resource.ID to Value.Text(receiptId),
                        PaymentReceiptFields.PROVIDER to Value.Text(provider.id),
                        PaymentReceiptFields.EVENT_KEY to Value.Text(event.eventKey),
                        PaymentReceiptFields.PAYMENT_ID to Value.Text(sessionId),
                        PaymentReceiptFields.STATUS to Value.Text(targetStatus.toWire()),
                        PaymentReceiptFields.RECORDED_AT to Value.Timestamp(clock.now()),
                    ),
                )

                // Never downgrade an already-succeeded payment unless the new state is REFUNDED.
                if (previousStatus == PaymentStatus.SUCCEEDED && targetStatus != PaymentStatus.REFUNDED) {
                    return@transaction WebhookTxOutcome.NoTransition(sessionId)
                }
                if (previousStatus == targetStatus) {
                    return@transaction WebhookTxOutcome.NoTransition(sessionId)
                }

                val now = clock.now()
                val changes = mutableMapOf<String, Value?>(
                    PaymentLedgerFields.STATUS to Value.Text(targetStatus.toWire()),
                    PaymentLedgerFields.UPDATED_AT to Value.Timestamp(now),
                )
                event.paymentReference?.let { ref ->
                    changes[PaymentLedgerFields.PAYMENT_REFERENCE] = Value.Text(ref)
                }
                val updated = tx.update(ledgerResource, sessionId, changes) ?: sessionRecord
                syncTargetRecordStatus(tx, updated, targetStatus)
                WebhookTxOutcome.Settled(sessionId, PaymentSettlement(updated.toSessionView(), previousStatus, event.eventKey))
            }
        } catch (_: DuplicateIdException) {
            val winner = dataSource.get(receiptsResource, receiptId)?.text(PaymentReceiptFields.PAYMENT_ID)
            WebhookTxOutcome.Duplicate(sessionId = winner)
        }

        return when (outcome) {
            is WebhookTxOutcome.Duplicate -> WebhookResult(
                eventKey = event.eventKey,
                duplicate = true,
                sessionId = outcome.sessionId,
                status = targetStatus,
            )
            WebhookTxOutcome.Unmatched -> WebhookResult(
                eventKey = event.eventKey,
                duplicate = false,
                sessionId = null,
                status = targetStatus,
            )
            is WebhookTxOutcome.NoTransition -> WebhookResult(
                eventKey = event.eventKey,
                duplicate = false,
                sessionId = outcome.sessionId,
                status = targetStatus,
            )
            is WebhookTxOutcome.Settled -> {
                notifySettled(outcome.settlement)
                WebhookResult(
                    eventKey = event.eventKey,
                    duplicate = false,
                    sessionId = outcome.sessionId,
                    status = targetStatus,
                )
            }
        }
    }

    /**
     * Refunds a settled payment session [id] through its [Refundable] provider and updates the ledger and target record.
     */
    public suspend fun refund(
        id: String,
        reason: String = "requested_by_customer",
        caller: Identity? = null,
    ): PaymentSessionView {
        enforceAccess(refundAccess, caller)
        val record = dataSource.get(ledgerResource, id)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "No payment session '$id'.")
        val currentStatus = paymentStatusFromWire(record.text(PaymentLedgerFields.STATUS)!!)
        if (currentStatus != PaymentStatus.SUCCEEDED) {
            throw ApiException(ErrorCode.CONFLICT, "Only a succeeded payment can be refunded (session '$id' is ${currentStatus.toWire()}).")
        }

        val providerId = record.text(PaymentLedgerFields.PROVIDER)!!
        val provider = providers[providerId]
            ?: throw ApiException(ErrorCode.BAD_REQUEST, "Payment provider '$providerId' is not registered.")
        val refundable = provider as? Refundable
            ?: throw ApiException(ErrorCode.NOT_IMPLEMENTED, "Provider '$providerId' does not support programmatic refunds.")

        val receipt = try {
            refundable.refund(
                RefundRequest(
                    externalSessionId = record.text(PaymentLedgerFields.EXTERNAL_ID)!!,
                    paymentReference = record.text(PaymentLedgerFields.PAYMENT_REFERENCE),
                    amount = record.paymentAmount(),
                    reason = reason,
                ),
            )
        } catch (e: PaymentException) {
            throw ApiException(ErrorCode.UNAVAILABLE, e.message ?: "Refund failed at '$providerId'.", e)
        }

        val settlement = dataSource.transaction { tx ->
            val updated = tx.update(
                ledgerResource,
                id,
                mapOf(
                    PaymentLedgerFields.STATUS to Value.Text(PaymentStatus.REFUNDED.toWire()),
                    PaymentLedgerFields.UPDATED_AT to Value.Timestamp(clock.now()),
                ),
            ) ?: record
            syncTargetRecordStatus(tx, updated, PaymentStatus.REFUNDED)
            PaymentSettlement(updated.toSessionView(), PaymentStatus.SUCCEEDED, "refund:${receipt.refundId}")
        }
        notifySettled(settlement)
        return settlement.session
    }

    /** Queries the current balance of a [PayoutCapable] provider such as `kaia-usdt`. */
    public suspend fun balance(providerId: String): WalletBalance {
        val provider = providers[providerId]
            ?: throw ApiException(ErrorCode.NOT_FOUND, "Unknown payment provider '$providerId'.")
        val capable = provider as? PayoutCapable
            ?: throw ApiException(ErrorCode.NOT_IMPLEMENTED, "Provider '$providerId' does not support payouts.")
        return capable.balance()
    }

    /** Executes an outbound disbursement through a [PayoutCapable] provider (such as sending Kaia USDT). */
    public suspend fun payout(providerId: String, request: PayoutRequest): PayoutReceipt {
        val provider = providers[providerId]
            ?: throw ApiException(ErrorCode.NOT_FOUND, "Unknown payment provider '$providerId'.")
        val capable = provider as? PayoutCapable
            ?: throw ApiException(ErrorCode.NOT_IMPLEMENTED, "Provider '$providerId' does not support payouts.")
        return try {
            capable.payout(request)
        } catch (e: InsufficientFundsException) {
            throw ApiException(ErrorCode.REJECTED, e.message ?: "Insufficient payout funds.", e)
        } catch (e: PaymentException) {
            throw ApiException(ErrorCode.UNAVAILABLE, e.message ?: "Payout failed.", e)
        }
    }

    /**
     * Scans every [OnChainConfirmable] provider for new `Transfer` logs and settles unambiguous pending sessions
     * automatically (matching `payer` + `amount`, or a unique open `amount` when no payer address was pre-registered).
     * Returns how many sessions were settled.
     */
    public suspend fun reconcileOnChainTransfers(): Int {
        var settledCount = 0
        for ((providerId, provider) in providers) {
            val onChain = provider as? OnChainConfirmable ?: continue
            val pendingSessions = listPendingSessions(providerId)
            if (pendingSessions.isEmpty()) continue

            val fromBlock = scanCursors[providerId] ?: 0L
            val batch = onChain.scanIncomingTransfers(fromBlock = fromBlock)
            if (batch.lastScannedBlock >= fromBlock) {
                scanCursors[providerId] = batch.lastScannedBlock + 1L
            }

            for (transfer in batch.transfers) {
                val receiptKey = PaymentReceiptFields.receiptId(providerId, transfer.eventKey)
                if (dataSource.get(receiptsResource, receiptKey) != null) continue

                val candidate = matchPendingSession(pendingSessions, transfer) ?: continue
                val settlement = runCatching {
                    applyOnChainConfirmation(candidate.id(), providerId, transfer)
                }.getOrNull()
                if (settlement != null) {
                    settledCount++
                    notifySettled(settlement)
                }
            }
        }
        return settledCount
    }

    private fun matchPendingSession(pending: List<Record>, transfer: OnChainConfirmation): Record? {
        val now = clock.now()
        val active = pending.filter { rec ->
            val exp = (rec[PaymentLedgerFields.EXPIRES_AT] as? Value.Timestamp)?.value
            exp == null || exp >= now
        }
        // 1. Exact payer wallet + exact amount match
        val byPayer = active.filter { rec ->
            rec.text(PaymentLedgerFields.PAYER)?.equals(transfer.fromAddress, ignoreCase = true) == true &&
                rec.paymentAmount() == transfer.amount
        }
        if (byPayer.size == 1) return byPayer.single()

        // 2. Unambiguous single pending session with no pre-bound payer and exact amount match
        val withoutPayer = active.filter { rec ->
            rec.text(PaymentLedgerFields.PAYER) == null && rec.paymentAmount() == transfer.amount
        }
        return withoutPayer.singleOrNull()
    }

    private suspend fun listPendingSessions(providerId: String): List<Record> {
        val collected = mutableListOf<Record>()
        var cursor: String? = null
        do {
            val page = dataSource.list(
                ledgerResource,
                Query(
                    filter = Filter.And(
                        listOf(
                            Filter.Compare(PaymentLedgerFields.PROVIDER, Comparison.EQ, Value.Text(providerId)),
                            Filter.Compare(PaymentLedgerFields.STATUS, Comparison.EQ, Value.Text(PaymentStatus.PENDING.toWire())),
                        ),
                    ),
                    limit = 100,
                    cursor = cursor,
                ),
            )
            collected += page.items
            cursor = page.nextCursor
        } while (cursor != null)
        return collected
    }

    private suspend fun applyOnChainConfirmation(
        paymentId: String,
        providerId: String,
        confirmation: OnChainConfirmation,
    ): PaymentSettlement? {
        val receiptId = PaymentReceiptFields.receiptId(providerId, confirmation.eventKey)
        return try {
            dataSource.transaction { tx ->
                val existingReceipt = tx.get(receiptsResource, receiptId)
                if (existingReceipt != null) {
                    val ownerId = existingReceipt.text(PaymentReceiptFields.PAYMENT_ID)
                    if (ownerId == paymentId) return@transaction null
                    throw ApiException(
                        ErrorCode.CONFLICT,
                        "On-chain transfer '${confirmation.txHash}' (log ${confirmation.logIndex}) has already been claimed by another payment.",
                    )
                }

                val current = tx.get(ledgerResource, paymentId)
                    ?: throw ApiException(ErrorCode.NOT_FOUND, "No payment session '$paymentId'.")
                val status = paymentStatusFromWire(current.text(PaymentLedgerFields.STATUS)!!)
                if (status == PaymentStatus.SUCCEEDED && current.text(PaymentLedgerFields.TX_HASH) == confirmation.txHash) {
                    return@transaction null
                }
                if (status != PaymentStatus.PENDING) {
                    throw ApiException(ErrorCode.CONFLICT, "Payment session '$paymentId' is already ${status.toWire()}.")
                }

                tx.create(
                    receiptsResource,
                    mapOf(
                        Resource.ID to Value.Text(receiptId),
                        PaymentReceiptFields.PROVIDER to Value.Text(providerId),
                        PaymentReceiptFields.EVENT_KEY to Value.Text(confirmation.eventKey),
                        PaymentReceiptFields.PAYMENT_ID to Value.Text(paymentId),
                        PaymentReceiptFields.STATUS to Value.Text(PaymentStatus.SUCCEEDED.toWire()),
                        PaymentReceiptFields.RECORDED_AT to Value.Timestamp(clock.now()),
                    ),
                )

                val now = clock.now()
                val updated = tx.update(
                    ledgerResource,
                    paymentId,
                    mapOf(
                        PaymentLedgerFields.STATUS to Value.Text(PaymentStatus.SUCCEEDED.toWire()),
                        PaymentLedgerFields.TX_HASH to Value.Text(confirmation.txHash),
                        PaymentLedgerFields.PAYER to Value.Text(confirmation.fromAddress),
                        PaymentLedgerFields.PAYMENT_REFERENCE to Value.Text(confirmation.txHash),
                        PaymentLedgerFields.UPDATED_AT to Value.Timestamp(now),
                    ),
                ) ?: current
                syncTargetRecordStatus(tx, updated, PaymentStatus.SUCCEEDED)
                PaymentSettlement(updated.toSessionView(), status, confirmation.eventKey)
            }
        } catch (e: DuplicateIdException) {
            val winner = dataSource.get(receiptsResource, receiptId)?.text(PaymentReceiptFields.PAYMENT_ID)
            if (winner == paymentId) return null
            throw ApiException(
                ErrorCode.CONFLICT,
                "On-chain transfer '${confirmation.txHash}' (log ${confirmation.logIndex}) has already been claimed by another payment.",
                e,
            )
        }
    }

    private suspend fun findSessionForWebhook(
        tx: DataSource,
        providerId: String,
        referenceId: String?,
        externalSessionId: String?,
    ): Record? {
        if (!referenceId.isNullOrBlank()) {
            val byId = tx.get(ledgerResource, referenceId)
            if (byId != null && byId.text(PaymentLedgerFields.PROVIDER) == providerId) return byId
        }
        if (!externalSessionId.isNullOrBlank()) {
            val page = tx.list(
                ledgerResource,
                Query(
                    filter = Filter.And(
                        listOf(
                            Filter.Compare(PaymentLedgerFields.PROVIDER, Comparison.EQ, Value.Text(providerId)),
                            Filter.Compare(PaymentLedgerFields.EXTERNAL_ID, Comparison.EQ, Value.Text(externalSessionId)),
                        ),
                    ),
                    limit = 1,
                ),
            )
            return page.items.firstOrNull()
        }
        return null
    }

    private suspend fun syncTargetRecordStatus(tx: DataSource, paymentRecord: Record, status: PaymentStatus) {
        val targetResourceName = paymentRecord.text(PaymentLedgerFields.RESOURCE) ?: return
        val targetRecordId = paymentRecord.text(PaymentLedgerFields.RECORD_ID) ?: return
        val targetResource = schema.resource(targetResourceName) ?: return
        val payable = targetResource.payable ?: return
        val statusField = payable.statusField ?: return
        val newValue = when (status) {
            PaymentStatus.SUCCEEDED -> payable.paidValue
            PaymentStatus.FAILED -> payable.failedValue
            PaymentStatus.REFUNDED -> payable.refundedValue
            else -> null
        } ?: return
        tx.update(targetResource, targetRecordId, mapOf(statusField to Value.Text(newValue)))
    }

    private suspend fun expireIfPastDeadline(record: Record): Record {
        val status = paymentStatusFromWire(record.text(PaymentLedgerFields.STATUS)!!)
        if (status != PaymentStatus.PENDING) return record
        val expiresAt = (record[PaymentLedgerFields.EXPIRES_AT] as? Value.Timestamp)?.value ?: return record
        val now = clock.now()
        if (now <= expiresAt) return record
        return dataSource.update(
            ledgerResource,
            record.id(),
            mapOf(
                PaymentLedgerFields.STATUS to Value.Text(PaymentStatus.EXPIRED.toWire()),
                PaymentLedgerFields.UPDATED_AT to Value.Timestamp(now),
            ),
        ) ?: record
    }

    private fun enforceAccess(access: Access, caller: Identity?) {
        if (access.allows(caller?.roles)) return
        if (caller == null) {
            throw ApiException(ErrorCode.UNAUTHORIZED, "Authentication is required.")
        }
        throw ApiException(ErrorCode.FORBIDDEN, "Your roles do not allow this payment operation.")
    }

    private fun enforceSessionOwnershipIfScoped(record: Record, caller: Identity?) {
        val actor = record.text(PaymentLedgerFields.ACTOR) ?: return
        if (readAccess == Access.Everyone) return
        if (caller?.id == actor) return
        if (refundAccess.allows(caller?.roles)) return
        if (caller == null) throw ApiException(ErrorCode.UNAUTHORIZED, "Authentication is required.")
        throw ApiException(ErrorCode.FORBIDDEN, "You cannot read another caller's payment session.")
    }

    private suspend fun notifySettled(settlement: PaymentSettlement) {
        for (listener in settlementListeners) {
            try {
                listener(settlement)
            } catch (e: Exception) {
                warnLogger("BaseKtPayments onSettled listener failed for '${settlement.session.id}'", e)
            }
        }
    }

    private fun Record.id(): String = (this[Resource.ID] as Value.Text).value

    private fun Record.text(field: String): String? = (this[field] as? Value.Text)?.value

    private fun Record.paymentAmount(): PaymentAmount = PaymentAmount(
        minorUnits = text(PaymentLedgerFields.MINOR_UNITS)!!,
        currency = text(PaymentLedgerFields.CURRENCY)!!,
        decimals = (this[PaymentLedgerFields.DECIMALS] as Value.Integer).value.toInt(),
    )

    private fun Record.toSessionView(): PaymentSessionView {
        val exactAmount = paymentAmount()
        val onChain = text(PaymentLedgerFields.ON_CHAIN_JSON)?.let {
            runCatching { json.decodeFromString(OnChainTransferInstruction.serializer(), it) }.getOrNull()
        }
        return PaymentSessionView(
            id = id(),
            provider = text(PaymentLedgerFields.PROVIDER)!!,
            externalId = text(PaymentLedgerFields.EXTERNAL_ID)!!,
            resource = text(PaymentLedgerFields.RESOURCE)!!,
            recordId = text(PaymentLedgerFields.RECORD_ID)!!,
            status = paymentStatusFromWire(text(PaymentLedgerFields.STATUS)!!),
            amount = exactAmount.toDecimalString(),
            minorUnits = exactAmount.minorUnits,
            decimals = exactAmount.decimals,
            currency = exactAmount.currency,
            checkoutUrl = text(PaymentLedgerFields.CHECKOUT_URL),
            onChain = onChain,
            txHash = text(PaymentLedgerFields.TX_HASH),
            payer = text(PaymentLedgerFields.PAYER),
            paymentReference = text(PaymentLedgerFields.PAYMENT_REFERENCE),
            createdAt = (this[PaymentLedgerFields.CREATED_AT] as Value.Timestamp).value.toString(),
            updatedAt = (this[PaymentLedgerFields.UPDATED_AT] as Value.Timestamp).value.toString(),
            expiresAt = (this[PaymentLedgerFields.EXPIRES_AT] as? Value.Timestamp)?.value?.toString(),
        )
    }
}

private sealed interface WebhookTxOutcome {
    data class Duplicate(val sessionId: String?) : WebhookTxOutcome
    data object Unmatched : WebhookTxOutcome
    data class NoTransition(val sessionId: String) : WebhookTxOutcome
    data class Settled(val sessionId: String, val settlement: PaymentSettlement) : WebhookTxOutcome
}
