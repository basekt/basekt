package io.github.basekt.payments

import io.github.basekt.schema.Extensible
import io.github.basekt.schema.FieldType
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.extension
import kotlinx.serialization.Serializable

/** The key a resource's [Payable] configuration is stored under in [Resource.extensions]. */
public const val PAYABLE_KEY: String = "payments.payable"

private val CURRENCY_CODE = Regex("[A-Z0-9]{2,12}")
private val AMOUNT_TYPES = setOf(FieldType.DECIMAL, FieldType.INT, FieldType.LONG)
private val TEXT_TYPES = setOf(FieldType.STRING, FieldType.ENUM)

/**
 * Declares how records of a resource are paid for through `BaseKtPayments`: which field holds the amount, where the
 * currency comes from, and which optional field is updated when payment settles or is refunded.
 *
 * Build one in a resource block with [payable].
 */
@Serializable
public data class Payable(
    /** Field on the resource holding the positive amount (`decimal`, `int` or `long`). */
    public val amountField: String,
    /** Field on the resource holding the currency code (`string` or `enum`), mutually exclusive with [fixedCurrency]. */
    public val currencyField: String? = null,
    /** Constant uppercase currency code (such as `"PHP"` or `"USDT"`), mutually exclusive with [currencyField]. */
    public val fixedCurrency: String? = null,
    /** Optional field shown as the checkout description (`string` or `enum`). */
    public val descriptionField: String? = null,
    /** Optional non-hidden `string` field holding the buyer's email address, forwarded to hosted checkouts when set. */
    public val buyerEmailField: String? = null,
    /** Optional field holding the expected payer wallet address (`string`), used for on-chain transfer matching. */
    public val payerAddressField: String? = null,
    /** Optional field (`string` or `enum`) updated automatically when a payment settles, fails or is refunded. */
    public val statusField: String? = null,
    /** Value written to [statusField] when a payment succeeds (`"paid"` by default when [statusField] is set). */
    public val paidValue: String? = null,
    /** Optional value written to [statusField] when a payment fails. */
    public val failedValue: String? = null,
    /** Optional value written to [statusField] when a payment is refunded. */
    public val refundedValue: String? = null,
)

/** Marks the `payable { }` DSL so inner blocks cannot accidentally invoke outer schema builders. */
@DslMarker
public annotation class PayableDsl

/** Builder for a resource's [Payable] extension. */
@PayableDsl
public class PayableBuilder internal constructor() {
    private var amountField: String? = null
    private var currencyField: String? = null
    private var fixedCurrency: String? = null
    private var descriptionField: String? = null
    private var buyerEmailField: String? = null
    private var payerAddressField: String? = null
    private var statusField: String? = null
    private var paidValue: String? = null
    private var failedValue: String? = null
    private var refundedValue: String? = null

    /** Names the numeric field (`decimal`, `int` or `long`) holding the amount to collect. Required. */
    public fun amount(field: String) {
        amountField = field
    }

    /** Names the `string` or `enum` field on each record holding its currency code (such as `"PHP"` or `"USDT"`). */
    public fun currency(field: String) {
        currencyField = field
        fixedCurrency = null
    }

    /** Uses a constant uppercase currency or token code (such as `"PHP"` or `"USDT"`) for every record of this resource. */
    public fun fixedCurrency(currencyCode: String) {
        fixedCurrency = currencyCode.trim().uppercase()
        currencyField = null
    }

    /** Names an optional `string` or `enum` field used as the payment session description. */
    public fun description(field: String) {
        descriptionField = field
    }

    /** Names an optional non-hidden `string` field holding the buyer's email address for hosted checkouts. */
    public fun buyerEmail(field: String) {
        buyerEmailField = field
    }

    /** Names an optional `string` field holding the payer's wallet address (`0x...`) for on-chain attribution. */
    public fun payerAddress(field: String) {
        payerAddressField = field
    }

    /**
     * Updates [field] in the same transaction when payment settles ([paid]), fails ([failed]) or is refunded
     * ([refunded]). Also prevents creating a second payment session once a record already holds [paid].
     */
    public fun statusField(
        field: String,
        paid: String = "paid",
        failed: String? = null,
        refunded: String? = null,
    ) {
        statusField = field
        paidValue = paid
        failedValue = failed
        refundedValue = refunded
    }

    internal fun build(): Payable {
        val resolvedAmount = requireNotNull(amountField) { "A payable block needs an amount field: amount(\"price\")." }
        require((currencyField != null) xor (fixedCurrency != null)) {
            "A payable block must declare either currency(\"currencyField\") or fixedCurrency(\"PHP\")."
        }
        return Payable(
            amountField = resolvedAmount,
            currencyField = currencyField,
            fixedCurrency = fixedCurrency,
            descriptionField = descriptionField,
            buyerEmailField = buyerEmailField,
            payerAddressField = payerAddressField,
            statusField = statusField,
            paidValue = paidValue,
            failedValue = failedValue,
            refundedValue = refundedValue,
        )
    }
}

/** Marks this resource as payable through `BaseKtPayments`. */
public fun Extensible.payable(block: PayableBuilder.() -> Unit) {
    extension(PAYABLE_KEY, Payable.serializer(), PayableBuilder().apply(block).build())
}

/** This resource's [Payable] settings, or `null` if it does not declare `payable { }`. */
public val Resource.payable: Payable? get() = extension<Payable>(PAYABLE_KEY)

/**
 * Checks every `payable { }` block in this schema and returns every problem found. Empty means every payable resource
 * matches its fields.
 */
public fun Schema.payableProblems(): List<String> {
    val problems = mutableListOf<String>()
    for (resource in resources) {
        val p = resource.payable ?: continue
        val at = "Payable of '${resource.name}'"
        if (resource.name in setOf("providers", "sessions", "webhooks")) {
            problems += "$at: resource name '${resource.name}' clashes with BaseKtPayments's routes."
        }
        if (resource.provided) {
            problems += "$at: '${resource.name}' is a provided read-only resource."
        }

        fun checkField(role: String, name: String, allowedTypes: Set<FieldType>) {
            val field = resource.field(name)
            when {
                field == null -> problems += "$at: $role '$name' is not a field of '${resource.name}'."
                field.list || field.type !in allowedTypes ->
                    problems += "$at: $role '$name' is a ${field.type.name.lowercase()}${if (field.list) " list" else ""}; expected ${allowedTypes.joinToString(" or ") { it.name.lowercase() }}."
            }
        }

        checkField("amount", p.amountField, AMOUNT_TYPES)
        if ((p.currencyField == null) == (p.fixedCurrency == null)) {
            problems += "$at: declare exactly one of currencyField or fixedCurrency."
        }
        p.currencyField?.let { checkField("currency", it, TEXT_TYPES) }
        p.fixedCurrency?.let { code ->
            if (!CURRENCY_CODE.matches(code)) {
                problems += "$at: fixedCurrency '$code' must be 2..12 uppercase ASCII letters or digits."
            }
        }
        p.descriptionField?.let { fieldName ->
            val field = resource.field(fieldName)
            when {
                field == null -> problems += "$at: description '$fieldName' is not a field of '${resource.name}'."
                field.hidden -> problems += "$at: description '$fieldName' is hidden, so it cannot be sent to a payment provider."
                field.list || field.type !in TEXT_TYPES ->
                    problems += "$at: description '$fieldName' must be a string or enum field."
            }
        }
        p.buyerEmailField?.let { fieldName ->
            val field = resource.field(fieldName)
            when {
                field == null -> problems += "$at: buyerEmail '$fieldName' is not a field of '${resource.name}'."
                field.hidden -> problems += "$at: buyerEmail '$fieldName' is hidden, so it cannot be sent to a payment provider."
                field.list || field.type != FieldType.STRING ->
                    problems += "$at: buyerEmail '$fieldName' must be a string field."
            }
        }
        p.payerAddressField?.let { checkField("payerAddress", it, setOf(FieldType.STRING)) }
        p.statusField?.let { statusName ->
            val field = resource.field(statusName)
            when {
                field == null -> problems += "$at: statusField '$statusName' is not a field of '${resource.name}'."
                field.list || field.type !in TEXT_TYPES ->
                    problems += "$at: statusField '$statusName' must be a string or enum field."
                field.type == FieldType.ENUM -> {
                    val allowed = field.values.orEmpty()
                    for ((label, candidate) in listOf("paid" to p.paidValue, "failed" to p.failedValue, "refunded" to p.refundedValue)) {
                        if (candidate != null && candidate !in allowed) {
                            problems += "$at: $label value '$candidate' is not one of '$statusName''s enum values (${allowed.joinToString()})."
                        }
                    }
                }
            }
        }
    }
    return problems
}
