package io.github.basekt.payments

import io.github.basekt.payment.PaymentStatus
import io.github.basekt.schema.Access
import io.github.basekt.schema.FieldType
import io.github.basekt.schema.IdSource
import io.github.basekt.schema.Resource
import io.github.basekt.schema.SchemaBuilder
import io.github.basekt.schema.schema
import java.security.MessageDigest
import java.util.Base64

/** Field names of the payments ledger resource declared by [paymentLedger]. */
public object PaymentLedgerFields {
    /** Provider id (such as `"paymongo"` or `"kaia-usdt"`). */
    public const val PROVIDER: String = "provider"

    /** Provider's session, checkout or link id. */
    public const val EXTERNAL_ID: String = "externalId"

    /** Target payable resource name. */
    public const val RESOURCE: String = "resource"

    /** Target payable record id. */
    public const val RECORD_ID: String = "recordId"

    /** Human-readable decimal amount. */
    public const val AMOUNT: String = "amount"

    /** Exact unsigned base-unit integer digits. */
    public const val MINOR_UNITS: String = "minorUnits"

    /** Currency decimal scale. */
    public const val DECIMALS: String = "decimals"

    /** Uppercase currency or token symbol. */
    public const val CURRENCY: String = "currency"

    /** Lifecycle state, one of [STATUSES]. */
    public const val STATUS: String = "status"

    /** Hosted checkout URL when redirect-based. */
    public const val CHECKOUT_URL: String = "checkoutUrl"

    /** Serialized `OnChainTransferInstruction` JSON when on-chain. */
    public const val ON_CHAIN_JSON: String = "onChainJson"

    /** Verified `0x` transaction hash when settled on-chain. */
    public const val TX_HASH: String = "txHash"

    /** Expected or verified payer wallet/identifier. */
    public const val PAYER: String = "payer"

    /** Underlying provider payment/charge reference (such as `pay_...`). */
    public const val PAYMENT_REFERENCE: String = "paymentReference"

    /** Authenticated caller id who created the session, if any. */
    public const val ACTOR: String = "actor"

    /** When the session was created. */
    public const val CREATED_AT: String = "createdAt"

    /** When the session was last updated. */
    public const val UPDATED_AT: String = "updatedAt"

    /** When the session expires, if bounded. */
    public const val EXPIRES_AT: String = "expiresAt"

    /** Wire values of [STATUS]. */
    public val STATUSES: List<String> = listOf("pending", "succeeded", "failed", "expired", "refunded")
}

/** Field names of the idempotency / replay-dedup receipts resource declared by [paymentLedger]. */
public object PaymentReceiptFields {
    /** Provider id. */
    public const val PROVIDER: String = "provider"

    /** Provider webhook event id or `<chainId>:<txHash>:<logIndex>` transfer key. */
    public const val EVENT_KEY: String = "eventKey"

    /** The payment session id that claimed this receipt. */
    public const val PAYMENT_ID: String = "paymentId"

    /** Target status applied when this receipt was recorded. */
    public const val STATUS: String = "status"

    /** When the receipt was recorded. */
    public const val RECORDED_AT: String = "recordedAt"

    /**
     * Computes the deterministic 48-character receipt primary key (`rcpt_<43-char-base64url-sha256>`) for [provider]
     * and [eventKey]. Fits every [io.github.basekt.data.DataSource] key constraint (`<= 64` characters for SQL
     * `VARCHAR(64)` primary keys, URL-safe, and free of `/` or `.` for Firestore) while keeping the full [provider]
     * and [eventKey] values in text columns.
     */
    public fun receiptId(provider: String, eventKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(provider.encodeToByteArray())
        digest.update(0)
        digest.update(eventKey.encodeToByteArray())
        return "rcpt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest())
    }
}

internal fun PaymentStatus.toWire(): String = when (this) {
    PaymentStatus.PENDING -> "pending"
    PaymentStatus.SUCCEEDED -> "succeeded"
    PaymentStatus.FAILED -> "failed"
    PaymentStatus.EXPIRED -> "expired"
    PaymentStatus.REFUNDED -> "refunded"
}

internal fun paymentStatusFromWire(wire: String): PaymentStatus = when (wire) {
    "pending" -> PaymentStatus.PENDING
    "succeeded" -> PaymentStatus.SUCCEEDED
    "failed" -> PaymentStatus.FAILED
    "expired" -> PaymentStatus.EXPIRED
    "refunded" -> PaymentStatus.REFUNDED
    else -> error("Unknown payment status '$wire'.")
}

/**
 * Returns a read-only payments ledger resource called [name]. Callers with one of [readers] may read it; nobody may
 * write it directly through the REST or admin APIs.
 */
public fun paymentLedgerResource(name: String = "payments", readers: Set<String> = emptySet()): Resource =
    schema {
        resource(name) { configurePaymentLedgerFields(readers) }
    }.resource(name)!!

/**
 * Returns a read-only payment receipts resource called [name] with deterministic `rcpt_…` natural ids
 * ([PaymentReceiptFields.receiptId]) for atomic webhook deduplication and on-chain transfer replay protection.
 */
public fun paymentReceiptsResource(name: String = "paymentReceipts", readers: Set<String> = emptySet()): Resource =
    schema {
        resource(name) { configurePaymentReceiptsFields(readers) }
    }.resource(name)!!

/**
 * Declares both read-only ledger resources (`payments` and `paymentReceipts` by default) required by
 * `install(BaseKtPayments)`. Callers with one of [readers] may inspect them in the REST and admin APIs.
 */
public fun SchemaBuilder.paymentLedger(
    name: String = "payments",
    receipts: String = "paymentReceipts",
    readers: Set<String> = emptySet(),
) {
    resource(name) { configurePaymentLedgerFields(readers) }
    resource(receipts) { configurePaymentReceiptsFields(readers) }
}

private fun io.github.basekt.schema.ResourceBuilder.configurePaymentLedgerFields(readers: Set<String>) {
    string(PaymentLedgerFields.PROVIDER) { required() }
    string(PaymentLedgerFields.EXTERNAL_ID) { required() }
    string(PaymentLedgerFields.RESOURCE) { required() }
    string(PaymentLedgerFields.RECORD_ID) { required() }
    decimal(PaymentLedgerFields.AMOUNT) { required() }
    string(PaymentLedgerFields.MINOR_UNITS) { required() }
    int(PaymentLedgerFields.DECIMALS) { required() }
    string(PaymentLedgerFields.CURRENCY) { required() }
    enum(PaymentLedgerFields.STATUS, PaymentLedgerFields.STATUSES) { required() }
    string(PaymentLedgerFields.CHECKOUT_URL)
    string(PaymentLedgerFields.ON_CHAIN_JSON)
    string(PaymentLedgerFields.TX_HASH)
    string(PaymentLedgerFields.PAYER)
    string(PaymentLedgerFields.PAYMENT_REFERENCE)
    string(PaymentLedgerFields.ACTOR)
    instant(PaymentLedgerFields.CREATED_AT) { required() }
    instant(PaymentLedgerFields.UPDATED_AT) { required() }
    instant(PaymentLedgerFields.EXPIRES_AT)
    permissions { if (readers.isNotEmpty()) read(*readers.toTypedArray()) }
}

private fun io.github.basekt.schema.ResourceBuilder.configurePaymentReceiptsFields(readers: Set<String>) {
    naturalId()
    string(PaymentReceiptFields.PROVIDER) { required() }
    string(PaymentReceiptFields.EVENT_KEY) { required() }
    string(PaymentReceiptFields.PAYMENT_ID) { required() }
    string(PaymentReceiptFields.STATUS) { required() }
    instant(PaymentReceiptFields.RECORDED_AT) { required() }
    permissions { if (readers.isNotEmpty()) read(*readers.toTypedArray()) }
}

internal fun paymentLedgerProblems(payments: Resource, receipts: Resource): List<String> {
    val problems = mutableListOf<String>()

    val expectedPayments = mapOf(
        PaymentLedgerFields.PROVIDER to FieldType.STRING,
        PaymentLedgerFields.EXTERNAL_ID to FieldType.STRING,
        PaymentLedgerFields.RESOURCE to FieldType.STRING,
        PaymentLedgerFields.RECORD_ID to FieldType.STRING,
        PaymentLedgerFields.AMOUNT to FieldType.DECIMAL,
        PaymentLedgerFields.MINOR_UNITS to FieldType.STRING,
        PaymentLedgerFields.DECIMALS to FieldType.INT,
        PaymentLedgerFields.CURRENCY to FieldType.STRING,
        PaymentLedgerFields.STATUS to FieldType.ENUM,
        PaymentLedgerFields.CHECKOUT_URL to FieldType.STRING,
        PaymentLedgerFields.ON_CHAIN_JSON to FieldType.STRING,
        PaymentLedgerFields.TX_HASH to FieldType.STRING,
        PaymentLedgerFields.PAYER to FieldType.STRING,
        PaymentLedgerFields.PAYMENT_REFERENCE to FieldType.STRING,
        PaymentLedgerFields.ACTOR to FieldType.STRING,
        PaymentLedgerFields.CREATED_AT to FieldType.INSTANT,
        PaymentLedgerFields.UPDATED_AT to FieldType.INSTANT,
        PaymentLedgerFields.EXPIRES_AT to FieldType.INSTANT,
    )
    checkReadOnlyLedger(payments, expectedPayments, problems)
    val statuses = payments.field(PaymentLedgerFields.STATUS)?.values
    if (statuses != null && !statuses.containsAll(PaymentLedgerFields.STATUSES)) {
        problems += "'${payments.name}.${PaymentLedgerFields.STATUS}' must allow ${PaymentLedgerFields.STATUSES.joinToString()}"
    }

    val expectedReceipts = mapOf(
        PaymentReceiptFields.PROVIDER to FieldType.STRING,
        PaymentReceiptFields.EVENT_KEY to FieldType.STRING,
        PaymentReceiptFields.PAYMENT_ID to FieldType.STRING,
        PaymentReceiptFields.STATUS to FieldType.STRING,
        PaymentReceiptFields.RECORDED_AT to FieldType.INSTANT,
    )
    checkReadOnlyLedger(receipts, expectedReceipts, problems)
    if (receipts.id.source != IdSource.NATURAL) {
        problems += "'${receipts.name}' must use naturalId()"
    }

    return problems
}

private fun checkReadOnlyLedger(
    resource: Resource,
    expected: Map<String, FieldType>,
    problems: MutableList<String>,
) {
    if (resource.provided) problems += "'${resource.name}' cannot be a provided read-only resource"
    for ((name, type) in expected) {
        val field = resource.field(name)
        when {
            field == null -> problems += "'${resource.name}' has no '$name' field"
            field.type != type -> problems += "'${resource.name}.$name' is a ${field.type.name.lowercase()} field, not a ${type.name.lowercase()} one"
            field.auto != null -> problems += "'${resource.name}.$name' is set by BaseKtPayments, not auto"
        }
    }
    val writable = listOf(
        "create" to resource.permissions.create,
        "update" to resource.permissions.update,
        "delete" to resource.permissions.delete,
    ).filter { it.second != Access.Nobody }.map { it.first }
    if (writable.isNotEmpty()) {
        problems += "callers may ${writable.joinToString()} entries in '${resource.name}'"
    }
    if (resource.actions.isNotEmpty()) {
        problems += "'${resource.name}' has actions"
    }
}
