package io.github.basekt.payment

/**
 * Thrown when a [PaymentProvider] refuses or fails an operation.
 */
public open class PaymentException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Thrown by [WebhookVerifiable.verifyWebhook] when a webhook header or signature parameter is missing, malformed,
 * outside the allowed replay tolerance, or fails cryptographic signature verification.
 */
public class WebhookSignatureException(
    message: String,
    cause: Throwable? = null,
) : PaymentException(message, cause)

/**
 * Thrown by [WebhookVerifiable.verifyWebhook] when an incoming webhook or pingback originates from an unauthorized
 * sender IP address (for example outside a provider's pingback IP allowlist).
 */
public class WebhookForbiddenException(
    message: String,
    cause: Throwable? = null,
) : PaymentException(message, cause)

/**
 * Thrown by [OnChainConfirmable.confirmTransaction] when a submitted transaction hash is malformed, missing, reverted,
 * unconfirmed, emitted by another contract, sent to another recipient, or below the expected amount.
 */
public class PaymentVerificationException(
    message: String,
    cause: Throwable? = null,
) : PaymentException(message, cause)

/**
 * Thrown by [PayoutCapable.payout] when the sender wallet or merchant account lacks either the payout balance or the
 * native network fee balance needed to execute the transfer.
 */
public class InsufficientFundsException(
    message: String,
    cause: Throwable? = null,
) : PaymentException(message, cause)
