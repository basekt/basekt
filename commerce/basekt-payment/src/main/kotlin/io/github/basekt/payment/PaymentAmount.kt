package io.github.basekt.payment

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.BigInteger

private val CURRENCY_PATTERN = Regex("[A-Z0-9]{2,12}")
private val UNSIGNED_DIGITS = Regex("\\d+")

/**
 * Default decimal places for common fiat and token currencies when an application does not override them.
 */
public object CurrencyDecimals {
    private val defaults: Map<String, Int> = mapOf(
        "PHP" to 2,
        "USD" to 2,
        "EUR" to 2,
        "GBP" to 2,
        "SGD" to 2,
        "JPY" to 0,
        "KRW" to 0,
        "USDT" to 6,
        "USDC" to 6,
        "KAIA" to 18,
    )

    /** Returns the default decimal scale for [currency] (such as `2` for `PHP` or `6` for `USDT`), or `null` if unknown. */
    public fun forCurrency(currency: String): Int? = defaults[currency.trim().uppercase()]
}

/**
 * An exact non-negative payment amount in a [currency], stored in its smallest [decimals]-scaled base unit as canonical
 * unsigned digits ([minorUnits]) so neither 2-decimal centavos nor 6/18-decimal on-chain token units lose precision or
 * overflow 64-bit integers.
 *
 * @property minorUnits the base-unit count as canonical unsigned decimal digits (for example `"10050"` for `100.50 PHP`
 *   at `decimals = 2`, or `"10500000"` for `10.5 USDT` at `decimals = 6`).
 * @property currency the uppercase currency or token code, such as `"PHP"` or `"USDT"`.
 * @property decimals the number of fractional decimal places (`0..36`).
 */
@Serializable
public data class PaymentAmount(
    public val minorUnits: String,
    public val currency: String,
    public val decimals: Int,
) : Comparable<PaymentAmount> {
    init {
        require(CURRENCY_PATTERN.matches(currency)) {
            "Currency '$currency' must be 2..12 uppercase ASCII letters or digits (such as PHP or USDT)."
        }
        require(decimals in 0..36) {
            "Decimals ($decimals) for '$currency' must be between 0 and 36."
        }
        require(UNSIGNED_DIGITS.matches(minorUnits)) {
            "minorUnits '$minorUnits' must be non-negative decimal digits."
        }
        require(minorUnits == "0" || !minorUnits.startsWith('0')) {
            "minorUnits '$minorUnits' must not have leading zeros."
        }
    }

    /** Returns the base-unit count as a [BigInteger]. */
    public fun rawUnits(): BigInteger = BigInteger(minorUnits)

    /** True when [minorUnits] is greater than zero. */
    public val isPositive: Boolean get() = minorUnits != "0"

    /**
     * Formats this amount as a plain decimal string without scientific notation, trimming trailing fractional zeros
     * unless [fixedScale] is `true`.
     */
    public fun toDecimalString(fixedScale: Boolean = false): String {
        val bd = BigDecimal(rawUnits(), decimals)
        return if (fixedScale) bd.toPlainString() else bd.stripTrailingZeros().let {
            if (it.scale() < 0) it.setScale(0).toPlainString() else it.toPlainString()
        }
    }

    override fun compareTo(other: PaymentAmount): Int {
        require(currency == other.currency && decimals == other.decimals) {
            "Cannot compare $currency (decimals=$decimals) with ${other.currency} (decimals=${other.decimals})."
        }
        return rawUnits().compareTo(other.rawUnits())
    }

    /** Factories for [PaymentAmount]. */
    public companion object {
        /** Builds a [PaymentAmount] from a non-negative [raw] base-unit count. */
        public fun ofMinorUnits(
            raw: BigInteger,
            currency: String,
            decimals: Int = requireDefaultDecimals(currency),
        ): PaymentAmount {
            require(raw >= BigInteger.ZERO) { "Payment amount cannot be negative ($raw)." }
            return PaymentAmount(
                minorUnits = raw.toString(),
                currency = currency.trim().uppercase(),
                decimals = decimals,
            )
        }

        /** Builds a [PaymentAmount] from a non-negative [raw] base-unit count. */
        public fun ofMinorUnits(
            raw: Long,
            currency: String,
            decimals: Int = requireDefaultDecimals(currency),
        ): PaymentAmount = ofMinorUnits(BigInteger.valueOf(raw), currency, decimals)

        /**
         * Parses a plain decimal string such as `"125.50"` or `"10.5"` into a [PaymentAmount]. Refuses negative values
         * and values with more fractional digits than [decimals] so precision is never silently truncated.
         */
        public fun ofDecimal(
            decimal: String,
            currency: String,
            decimals: Int = requireDefaultDecimals(currency),
        ): PaymentAmount {
            val normalizedCurrency = currency.trim().uppercase()
            val trimmed = decimal.trim()
            val bd = try {
                BigDecimal(trimmed)
            } catch (e: NumberFormatException) {
                throw IllegalArgumentException("'$decimal' is not a valid decimal amount.", e)
            }
            require(bd >= BigDecimal.ZERO) { "Payment amount '$decimal' must not be negative." }
            val stripped = bd.stripTrailingZeros()
            val actualScale = maxOf(0, stripped.scale())
            require(actualScale <= decimals) {
                "Amount '$decimal' has $actualScale fractional digits, exceeding $normalizedCurrency's $decimals decimals."
            }
            val raw = bd.movePointRight(decimals).toBigIntegerExact()
            return ofMinorUnits(raw, normalizedCurrency, decimals)
        }

        private fun requireDefaultDecimals(currency: String): Int {
            val normalized = currency.trim().uppercase()
            return requireNotNull(CurrencyDecimals.forCurrency(normalized)) {
                "Unknown decimal scale for '$normalized'; pass decimals explicitly."
            }
        }
    }
}
