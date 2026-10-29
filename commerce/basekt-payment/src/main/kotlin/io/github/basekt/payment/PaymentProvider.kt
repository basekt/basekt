package io.github.basekt.payment

/**
 * A payment rail or gateway adapter (in memory, PayMongo, Kaia USDT...). Every adapter implements this interface and
 * passes `PaymentProviderContract` in `testing:basekt-testing`.
 *
 * Optional capabilities are separate interfaces so callers and `BaseKtPayments` can discover what each provider can do
 * with `provider as? WebhookVerifiable`, `provider as? OnChainConfirmable`, `provider as? Refundable`, and
 * `provider as? PayoutCapable`:
 * - Hosted redirect / webhook rails (such as PayMongo) implement [WebhookVerifiable] and [Refundable].
 * - On-chain token rails (such as Kaia USDT) implement [OnChainConfirmable] and optionally [PayoutCapable].
 *
 * Every function is `suspend` and safe to call concurrently.
 */
public interface PaymentProvider {
    /** Stable identifier for this provider in URLs, configuration and ledger records (such as `"paymongo"` or `"kaia-usdt"`). */
    public val id: String

    /** Uppercase currency or token codes this provider accepts (such as `setOf("PHP")` or `setOf("USDT")`). */
    public val supportedCurrencies: Set<String>

    /** Creates a new payment session for [request]. Throws [PaymentException] if the provider rejects it. */
    public suspend fun createSession(request: CreatePaymentRequest): ProviderSession

    /** Fetches the provider's current view of [externalId], or returns `null` if no such session exists. */
    public suspend fun getSession(externalId: String): ProviderSession?
}

/**
 * Implemented by a [PaymentProvider] that pushes signed HTTP webhooks or pingbacks (such as PayMongo, Paymentwall, or
 * Lemon Squeezy).
 */
public interface WebhookVerifiable {
    /**
     * Optional plain-text response body required by the provider's webhook/pingback listener contract (for example
     * `"OK"` for Paymentwall). When `null` (`null` by default), `BaseKtPayments` responds with a JSON `WebhookResult`.
     */
    public val plainTextAck: String?
        get() = null

    /**
     * Verifies the cryptographic signature (and optional sender IP allowlist) on [payload] and decodes the event.
     * Throws [WebhookForbiddenException] when rejected by an IP allowlist, or [WebhookSignatureException] when the
     * signature or payload is missing, malformed, stale, or invalid.
     */
    public suspend fun verifyWebhook(payload: WebhookPayload): VerifiedPaymentEvent

    /**
     * Convenience overload that wraps [headers] and [rawBody] into a [WebhookPayload] and delegates to [verifyWebhook].
     */
    public suspend fun verifyWebhook(headers: Map<String, String>, rawBody: String): VerifiedPaymentEvent =
        verifyWebhook(WebhookPayload(rawBody = rawBody, headers = headers))
}

/**
 * Implemented by a [PaymentProvider] whose payments are settled by an on-chain token transfer (such as Kaia KIP-7/ERC-20 USDT).
 */
public interface OnChainConfirmable {
    /** Expected EVM chain id (for example `8217` for Kaia Mainnet or `1001` for Kairos). */
    public val chainId: Long

    /** Normalized lowercase `0x`-prefixed token contract address (for example `0xd077a400968890eacc75cdc901f0356c943e4fdb`). */
    public val tokenAddress: String

    /** Normalized lowercase `0x`-prefixed merchant wallet address that receives incoming payments. */
    public val recipientAddress: String

    /**
     * Inspects [txHash] on-chain and verifies chain id, receipt success, confirmation depth, exact [tokenAddress],
     * [recipientAddress], optional [expectedPayer], and [expectedAmount]. Throws [PaymentVerificationException] when
     * any check fails.
     */
    public suspend fun confirmTransaction(
        txHash: String,
        expectedAmount: PaymentAmount,
        expectedPayer: String? = null,
    ): OnChainConfirmation

    /**
     * Scans `Transfer` logs to [recipientAddress] on [tokenAddress] from [fromBlock] through [toBlock] (or the latest
     * confirmed block when `null`) so a server can reconcile transfers even if a browser closes before submitting `txHash`.
     */
    public suspend fun scanIncomingTransfers(fromBlock: Long, toBlock: Long? = null): OnChainTransferBatch
}

/**
 * Implemented by a [PaymentProvider] that can refund a settled payment.
 */
public interface Refundable {
    /** Executes a refund for [request]. Throws [PaymentException] on failure. */
    public suspend fun refund(request: RefundRequest): RefundReceipt
}

/**
 * Implemented by a [PaymentProvider] that can disburse outbound payouts (such as sending Kaia USDT from a hot wallet
 * or treasury signer).
 */
public interface PayoutCapable {
    /** Queries the current payout balance (and native gas balance when applicable). */
    public suspend fun balance(): WalletBalance

    /**
     * Sends [request] after checking balances and network fees, waiting for confirmation when applicable. Throws
     * [InsufficientFundsException] when token or gas funds are insufficient, or [PaymentException] on failure.
     */
    public suspend fun payout(request: PayoutRequest): PayoutReceipt
}
