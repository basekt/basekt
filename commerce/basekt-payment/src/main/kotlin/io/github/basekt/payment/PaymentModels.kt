package io.github.basekt.payment

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * The lifecycle state of a payment session.
 */
@Serializable
public enum class PaymentStatus {
    /** Waiting for the payer to complete checkout or broadcast an on-chain transfer. */
    @SerialName("pending")
    PENDING,

    /** Verified as paid (by signed webhook or confirmed on-chain transfer). */
    @SerialName("succeeded")
    SUCCEEDED,

    /** Rejected or failed at the payment rail or reverted on-chain. */
    @SerialName("failed")
    FAILED,

    /** Passed its expiration window without being paid. */
    @SerialName("expired")
    EXPIRED,

    /** Returned to the payer after having succeeded. */
    @SerialName("refunded")
    REFUNDED,
}

/**
 * Parameters passed to [PaymentProvider.createSession].
 *
 * @property referenceId the caller's unique payment id (stored in provider metadata or reference fields for correlation).
 * @property amount the exact positive amount to collect.
 * @property description a short human-readable summary shown at checkout when supported.
 * @property successUrl where hosted checkouts redirect after payment, if applicable.
 * @property cancelUrl where hosted checkouts redirect when cancelled, if applicable.
 * @property payerAddress an optional expected wallet address (such as `0x...`) for on-chain attribution.
 * @property expiresAt when the session expires, if bounded.
 * @property metadata string key-value metadata forwarded to providers that support it.
 */
@Serializable
public data class CreatePaymentRequest(
    public val referenceId: String,
    public val amount: PaymentAmount,
    public val description: String? = null,
    public val successUrl: String? = null,
    public val cancelUrl: String? = null,
    public val payerAddress: String? = null,
    public val expiresAt: Instant? = null,
    public val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(referenceId.isNotBlank()) { "referenceId must not be blank." }
        require(amount.isPositive) { "Payment amount must be greater than zero." }
    }
}

/**
 * Wallet instructions returned when a session settles via an on-chain token transfer (such as Kaia USDT).
 *
 * @property chainId the EVM chain id (for example `8217` on Kaia Mainnet or `1001` on Kairos).
 * @property networkName a display name for the network, such as `"Kaia Mainnet"`.
 * @property tokenAddress the lowercase `0x`-prefixed token contract address.
 * @property tokenSymbol the token ticker, such as `"USDT"`.
 * @property decimals the token decimal places, such as `6`.
 * @property recipientAddress the merchant treasury `0x`-prefixed wallet address that must receive the transfer.
 * @property amountRaw the exact base-unit integer string to transfer.
 * @property eip681Uri an EIP-681 payment request URI (`ethereum:<token>@<chainId>/transfer?address=...&uint256=...`).
 * @property explorerUrl an optional block-explorer URL for the token or recipient.
 */
@Serializable
public data class OnChainTransferInstruction(
    public val chainId: Long,
    public val networkName: String,
    public val tokenAddress: String,
    public val tokenSymbol: String,
    public val decimals: Int,
    public val recipientAddress: String,
    public val amountRaw: String,
    public val eip681Uri: String,
    public val explorerUrl: String? = null,
)

/**
 * A payment session created or retrieved from a [PaymentProvider].
 *
 * @property externalId the provider's own session, link, or intent identifier.
 * @property status the current status reported by the provider.
 * @property amount the session's amount.
 * @property checkoutUrl the hosted checkout URL for redirect-based providers (such as PayMongo), or `null` for direct on-chain flows.
 * @property onChain on-chain token transfer parameters when the provider settles on-chain, or `null` otherwise.
 * @property expiresAt when the session expires, if known.
 * @property paymentReference the underlying settlement or charge id once paid (for example PayMongo's `pay_...` or a `0x...` tx hash).
 * @property metadata provider-returned metadata.
 */
@Serializable
public data class ProviderSession(
    public val externalId: String,
    public val status: PaymentStatus,
    public val amount: PaymentAmount,
    public val checkoutUrl: String? = null,
    public val onChain: OnChainTransferInstruction? = null,
    public val expiresAt: Instant? = null,
    public val paymentReference: String? = null,
    public val metadata: Map<String, String> = emptyMap(),
)

/**
 * Transport-neutral snapshot of an incoming HTTP webhook or pingback passed to [WebhookVerifiable.verifyWebhook].
 *
 * @property rawBody the exact unparsed UTF-8 request body (empty on `GET` pingbacks).
 * @property headers incoming HTTP header names and values.
 * @property queryParameters incoming URL query parameters (`key=""` preserved when present with an empty value).
 * @property formParameters incoming `application/x-www-form-urlencoded` body parameters when posted as a form.
 * @property remoteIp the caller's resolved client IP address, if known (used by IP-allowlisted pingback rails).
 */
@Serializable
public data class WebhookPayload(
    public val rawBody: String = "",
    public val headers: Map<String, String> = emptyMap(),
    public val queryParameters: Map<String, String> = emptyMap(),
    public val formParameters: Map<String, String> = emptyMap(),
    public val remoteIp: String? = null,
)

/**
 * A cryptographically verified webhook or pingback notification decoded by [WebhookVerifiable.verifyWebhook].
 *
 * @property eventKey the unique provider delivery/event id used for idempotent deduplication.
 * @property status the target [PaymentStatus] implied by this event, or `null` when the event is informational only.
 * @property externalSessionId the provider session/link/intent id, if present in the payload.
 * @property referenceId the application's original [CreatePaymentRequest.referenceId], if present in metadata or reference fields.
 * @property paymentReference the provider's underlying payment/charge id (needed for refunds on some rails).
 * @property amount the amount reported by the event, if present.
 * @property occurredAt when the provider created the event, if present.
 * @property metadata provider-specific attributes decoded from the event (such as subscription renewal dates or item ids).
 */
@Serializable
public data class VerifiedPaymentEvent(
    public val eventKey: String,
    public val status: PaymentStatus?,
    public val externalSessionId: String? = null,
    public val referenceId: String? = null,
    public val paymentReference: String? = null,
    public val amount: PaymentAmount? = null,
    public val occurredAt: Instant? = null,
    public val metadata: Map<String, String> = emptyMap(),
)

/**
 * A verified on-chain token transfer log returned by [OnChainConfirmable].
 *
 * @property eventKey a canonical globally unique receipt key (`"<chainId>:<txHash>:<logIndex>"`) for replay protection.
 * @property chainId the verified chain id.
 * @property txHash the lowercase `0x`-prefixed 32-byte transaction hash.
 * @property logIndex the log index inside the transaction receipt.
 * @property blockNumber the block number in which the transaction was mined.
 * @property confirmations the number of confirmations at verification time (`>= 1`).
 * @property tokenAddress the lowercase `0x`-prefixed token contract that emitted `Transfer`.
 * @property fromAddress the lowercase `0x`-prefixed sender wallet address.
 * @property toAddress the lowercase `0x`-prefixed recipient wallet address.
 * @property amount the verified token amount transferred in that log.
 */
@Serializable
public data class OnChainConfirmation(
    public val eventKey: String,
    public val chainId: Long,
    public val txHash: String,
    public val logIndex: Int,
    public val blockNumber: Long,
    public val confirmations: Long,
    public val tokenAddress: String,
    public val fromAddress: String,
    public val toAddress: String,
    public val amount: PaymentAmount,
)

/**
 * A batch of incoming token transfers returned by [OnChainConfirmable.scanIncomingTransfers].
 *
 * @property lastScannedBlock the highest block number inspected in this scan.
 * @property transfers every verified incoming transfer to [OnChainConfirmable.recipientAddress] in the scanned range.
 */
@Serializable
public data class OnChainTransferBatch(
    public val lastScannedBlock: Long,
    public val transfers: List<OnChainConfirmation>,
)

/**
 * Parameters passed to [Refundable.refund].
 *
 * @property externalSessionId the provider's session or link id.
 * @property paymentReference the provider's underlying payment id, if distinct from [externalSessionId].
 * @property amount the exact positive amount to refund.
 * @property reason a short reason code or note.
 */
@Serializable
public data class RefundRequest(
    public val externalSessionId: String,
    public val paymentReference: String? = null,
    public val amount: PaymentAmount,
    public val reason: String = "requested_by_customer",
) {
    init {
        require(externalSessionId.isNotBlank()) { "externalSessionId must not be blank." }
        require(amount.isPositive) { "Refund amount must be greater than zero." }
    }
}

/**
 * Receipt returned by [Refundable.refund].
 *
 * @property refundId the provider's refund identifier.
 * @property amount the refunded amount.
 * @property status the provider's raw status string, such as `"succeeded"` or `"pending"`.
 */
@Serializable
public data class RefundReceipt(
    public val refundId: String,
    public val amount: PaymentAmount,
    public val status: String,
)

/**
 * Snapshot of a payout wallet's token and native fee balances returned by [PayoutCapable.balance].
 *
 * @property walletAddress the sender wallet address or account identifier.
 * @property tokenBalance the available payout balance in the configured token/currency.
 * @property nativeBalanceRaw the raw base-unit balance of the native gas currency (such as 18-decimal `KAIA`), if applicable.
 * @property nativeSymbol the native gas symbol (such as `"KAIA"`), if applicable.
 * @property nativeDecimals the native gas decimals (such as `18`), if applicable.
 */
@Serializable
public data class WalletBalance(
    public val walletAddress: String,
    public val tokenBalance: PaymentAmount,
    public val nativeBalanceRaw: String? = null,
    public val nativeSymbol: String? = null,
    public val nativeDecimals: Int? = null,
)

/**
 * Parameters passed to [PayoutCapable.payout] to disburse funds (such as Kaia USDT) to a recipient.
 *
 * @property recipientAddress the destination wallet or account address.
 * @property amount the exact positive amount to send.
 * @property referenceId an optional caller-supplied reference or idempotency tag.
 */
@Serializable
public data class PayoutRequest(
    public val recipientAddress: String,
    public val amount: PaymentAmount,
    public val referenceId: String? = null,
) {
    init {
        require(recipientAddress.isNotBlank()) { "recipientAddress must not be blank." }
        require(amount.isPositive) { "Payout amount must be greater than zero." }
    }
}

/**
 * Receipt returned by [PayoutCapable.payout].
 *
 * @property transferId the transaction hash or provider transfer id.
 * @property fromAddress the sender wallet or account identifier.
 * @property recipientAddress the destination wallet or account identifier.
 * @property amount the transferred amount.
 * @property blockNumber the block number when mined on-chain, if known.
 * @property explorerUrl an optional block-explorer link to the transaction.
 */
@Serializable
public data class PayoutReceipt(
    public val transferId: String,
    public val fromAddress: String,
    public val recipientAddress: String,
    public val amount: PaymentAmount,
    public val blockNumber: Long? = null,
    public val explorerUrl: String? = null,
)
