package io.github.basekt.payment.memory

import io.github.basekt.payment.InsufficientFundsException
import io.github.basekt.payment.PaymentAmount
import io.github.basekt.payment.PaymentProvider
import io.github.basekt.payment.PaymentStatus
import io.github.basekt.payment.PayoutRequest
import io.github.basekt.testing.PaymentProviderContract
import kotlinx.coroutines.test.runTest
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InMemoryPaymentProviderTest : PaymentProviderContract() {
    override fun createPaymentProvider(): PaymentProvider = InMemoryPaymentProvider()

    @Test
    fun `simulates webhooks, on-chain transfers and payouts`() = runTest {
        val provider = InMemoryPaymentProvider(onChainMode = true)
        val usdt = PaymentAmount.ofDecimal("15.25", "USDT")
        val txHash = provider.recordOnChainTransfer(usdt)
        val confirmed = provider.confirmTransaction(txHash, usdt)
        assertEquals(txHash, confirmed.txHash)
        assertEquals(usdt, confirmed.amount)

        val (headers, rawBody) = provider.signedWebhook(
            eventId = "evt_42",
            status = PaymentStatus.SUCCEEDED,
            referenceId = "order-42",
        )
        val verified = provider.verifyWebhook(headers, rawBody)
        assertEquals("evt_42", verified.eventKey)
        assertEquals(PaymentStatus.SUCCEEDED, verified.status)

        provider.setWalletBalances(tokenRaw = BigInteger.valueOf(50_000_000L), nativeGasRaw = BigInteger("1000000000000000000"))
        val payout = provider.payout(PayoutRequest("0x3333333333333333333333333333333333333333", PaymentAmount.ofDecimal("20", "USDT")))
        assertEquals("0x3333333333333333333333333333333333333333", payout.recipientAddress)

        provider.setWalletBalances(tokenRaw = BigInteger.valueOf(50_000_000L), nativeGasRaw = BigInteger.ZERO)
        assertFailsWith<InsufficientFundsException> {
            provider.payout(PayoutRequest("0x3333333333333333333333333333333333333333", PaymentAmount.ofDecimal("5", "USDT")))
        }
    }
}
