package io.github.basekt.payment

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaymentAmountTest {
    @Test
    fun `parses and formats fiat centavos and 6-decimal USDT and 18-decimal KAIA without precision loss`() {
        val php = PaymentAmount.ofDecimal("149.95", "php")
        assertEquals("14995", php.minorUnits)
        assertEquals("PHP", php.currency)
        assertEquals(2, php.decimals)
        assertEquals("149.95", php.toDecimalString())
        assertTrue(php.isPositive)

        val usdt = PaymentAmount.ofDecimal("10.50", "USDT")
        assertEquals("10500000", usdt.minorUnits)
        assertEquals(6, usdt.decimals)
        assertEquals("10.5", usdt.toDecimalString())
        assertEquals("10.500000", usdt.toDecimalString(fixedScale = true))

        // Exceeds Long.MAX_VALUE (~9.22e18): 25.125 KAIA at 18 decimals
        val kaia = PaymentAmount.ofDecimal("25.125", "KAIA")
        assertEquals(BigInteger("25125000000000000000"), kaia.rawUnits())
        assertEquals("25.125", kaia.toDecimalString())

        val zero = PaymentAmount.ofMinorUnits(0L, "USDT")
        assertFalse(zero.isPositive)
        assertEquals("0", zero.toDecimalString())
    }

    @Test
    fun `refuses extra precision, negative numbers and cross-currency comparison`() {
        assertFailsWith<IllegalArgumentException> { PaymentAmount.ofDecimal("10.123", "PHP") }
        assertFailsWith<IllegalArgumentException> { PaymentAmount.ofDecimal("1.1234567", "USDT") }
        assertFailsWith<IllegalArgumentException> { PaymentAmount.ofDecimal("-1", "PHP") }
        assertFailsWith<IllegalArgumentException> { PaymentAmount("01", "PHP", 2) }
        assertFailsWith<IllegalArgumentException> {
            PaymentAmount.ofDecimal("10", "PHP").compareTo(PaymentAmount.ofDecimal("10", "USDT"))
        }
    }
}
