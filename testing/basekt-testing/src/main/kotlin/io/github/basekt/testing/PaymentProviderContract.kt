package io.github.basekt.testing

import io.github.basekt.payment.CreatePaymentRequest
import io.github.basekt.payment.OnChainConfirmable
import io.github.basekt.payment.PaymentAmount
import io.github.basekt.payment.PaymentException
import io.github.basekt.payment.PaymentProvider
import io.github.basekt.payment.PaymentStatus
import io.github.basekt.payment.PaymentVerificationException
import io.github.basekt.payment.WebhookPayload
import io.github.basekt.payment.WebhookSignatureException
import io.github.basekt.payment.WebhookVerifiable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The contract suite every [PaymentProvider] adapter must pass. Subclass it in the adapter's tests and return a
 * configured provider from [createPaymentProvider]. Providers that implement [WebhookVerifiable] or
 * [OnChainConfirmable] are also checked for negative/tamper cases.
 */
public abstract class PaymentProviderContract {
    /** Returns a fresh provider under test. */
    protected abstract fun createPaymentProvider(): PaymentProvider

    /** A currency the provider under test supports (`"PHP"` by default). */
    protected open val contractCurrency: String = "PHP"

    /** A positive amount in [contractCurrency] used by the suite. */
    protected open val contractAmount: PaymentAmount
        get() = PaymentAmount.ofDecimal("125", contractCurrency)

    /** Optional sender IP passed in [WebhookPayload.remoteIp] during [webhookRefusesMissingAndTamperedSignatures]. */
    protected open val contractWebhookRemoteIp: String? = null

    private fun test(block: suspend CoroutineScope.() -> Unit) = runTest(timeout = 2.minutes) { block() }

    /** A created session has a non-blank external id, starts pending, and can be read back by [PaymentProvider.getSession]. */
    @Test
    public fun createSessionThenReadBack(): Unit = test {
        val provider = createPaymentProvider()
        assertTrue(contractCurrency in provider.supportedCurrencies)
        val created = provider.createSession(
            CreatePaymentRequest(
                referenceId = "ref-contract-1",
                amount = contractAmount,
                description = "Contract item",
                successUrl = "https://shop.example/ok",
                cancelUrl = "https://shop.example/cancel",
            ),
        )
        assertTrue(created.externalId.isNotBlank())
        assertEquals(PaymentStatus.PENDING, created.status)
        assertEquals(contractAmount, created.amount)
        assertTrue(created.checkoutUrl != null || created.onChain != null, "A session must offer a checkoutUrl or onChain transfer instructions.")

        val fetched = assertNotNull(provider.getSession(created.externalId))
        assertEquals(created.externalId, fetched.externalId)
        assertEquals(contractAmount, fetched.amount)
        assertNull(provider.getSession("missing-session-id-999999"))
    }

    /** Asking a provider to bill in a currency outside [PaymentProvider.supportedCurrencies] fails with [PaymentException]. */
    @Test
    public fun unsupportedCurrencyIsRefused(): Unit = test {
        val provider = createPaymentProvider()
        assertFailsWith<PaymentException> {
            provider.createSession(
                CreatePaymentRequest(
                    referenceId = "ref-unsupported",
                    amount = PaymentAmount.ofDecimal("10", "ZZZ", decimals = 2),
                ),
            )
        }
    }

    /** Concurrent [PaymentProvider.createSession] calls produce distinct external ids and remain readable. */
    @Test
    public fun concurrentSessionCreation(): Unit = test {
        val provider = createPaymentProvider()
        val sessions = (1..12).map { index ->
            async(Dispatchers.IO) {
                provider.createSession(
                    CreatePaymentRequest(
                        referenceId = "ref-concurrent-$index",
                        amount = contractAmount,
                        description = "Concurrent #$index",
                        successUrl = "https://shop.example/ok",
                        cancelUrl = "https://shop.example/cancel",
                    ),
                )
            }
        }.awaitAll()
        assertEquals(12, sessions.map { it.externalId }.toSet().size)
        for (session in sessions) {
            assertNotNull(provider.getSession(session.externalId))
        }
    }

    /** A [WebhookVerifiable] provider refuses missing and tampered signatures with [WebhookSignatureException]. */
    @Test
    public fun webhookRefusesMissingAndTamperedSignatures(): Unit = test {
        val verifiable = createPaymentProvider() as? WebhookVerifiable ?: return@test
        val sampleBody = """{"data":{"id":"evt_1","type":"event"}}"""
        assertFailsWith<WebhookSignatureException> {
            verifiable.verifyWebhook(
                WebhookPayload(
                    rawBody = sampleBody,
                    remoteIp = contractWebhookRemoteIp,
                ),
            )
        }
        assertFailsWith<WebhookSignatureException> {
            verifiable.verifyWebhook(
                WebhookPayload(
                    rawBody = sampleBody,
                    headers = mapOf(
                        "Paymongo-Signature" to "t=1700000000,te=deadbeef,li=",
                        "X-Signature" to "deadbeef",
                    ),
                    queryParameters = mapOf(
                        "uid" to "u1",
                        "goodsid" to "g1",
                        "ref" to "r1",
                        "type" to "0",
                        "sign_version" to "3",
                        "sign" to "deadbeef",
                    ),
                    remoteIp = contractWebhookRemoteIp,
                ),
            )
        }
    }

    /** An [OnChainConfirmable] provider refuses malformed and unknown transaction hashes with [PaymentVerificationException]. */
    @Test
    public fun onChainRefusesMalformedAndUnknownHashes(): Unit = test {
        val onChain = createPaymentProvider() as? OnChainConfirmable ?: return@test
        assertFailsWith<PaymentVerificationException> {
            onChain.confirmTransaction("not-a-valid-hash", contractAmount)
        }
        assertFailsWith<PaymentVerificationException> {
            onChain.confirmTransaction("0x" + "f".repeat(64), contractAmount)
        }
    }
}
