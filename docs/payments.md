# Payments

`install(BaseKtPayments)` (`basekt-payments`) collects payments for records in your schema across hosted fiat checkouts,
merchant-of-record gateways and on-chain token rails, deduplicates webhooks, pingbacks and transaction logs in the
database, updates your record's status in the same transaction, and exposes programmatic refunds and outbound payouts.

Providers sit behind the Ktor-server-free `PaymentProvider` SPI (`basekt-payment`), so a single application can accept
Philippine Peso (`PHP`) via PayMongo (`basekt-payment-paymongo`), global cards and regional wallets (`USD`, `EUR`,
`PHP`...) via Paymentwall (`basekt-payment-paymentwall`) or Lemon Squeezy (`basekt-payment-lemonsqueezy`), and Tether
USD (`USDT`) on Kaia (`basekt-payment-kaia`) without coupling your routes or schema to any provider's wire format.
Outbound HTTP providers accept a shared Ktor `HttpClient` (including `MockEngine` in unit tests).

```kotlin
dependencies {
    implementation("io.github.basekt:basekt-payments:0.3.0-SNAPSHOT")
    implementation("io.github.basekt:basekt-payment-paymongo:0.3.0-SNAPSHOT")
    implementation("io.github.basekt:basekt-payment-paymentwall:0.3.0-SNAPSHOT")
    implementation("io.github.basekt:basekt-payment-lemonsqueezy:0.3.0-SNAPSHOT")
    implementation("io.github.basekt:basekt-payment-kaia:0.3.0-SNAPSHOT")
}
```

## Declaring the ledger and payable resources

Call `paymentLedger()` in your schema to add two read-only resources — `payments` (every session and its lifecycle) and
`paymentReceipts` (keyed by `(provider, eventKey)` for atomic webhook deduplication and on-chain replay protection) —
and mark any resource that can be paid for with `payable { }`:

```kotlin
val appSchema = schema {
    paymentLedger(readers = setOf("admin"))

    resource("editions") {
        string("title") { required() }
        decimal("price") { required(); min("0.01") }
        enum("currency", listOf("PHP", "USD", "USDT")) { required() }
        string("buyerEmail")
        string("wallet")
        enum("state", listOf("reserved", "paid", "failed", "refunded")) { required() }

        permissions {
            read(everyone)
            write("staff")
        }

        payable {
            amount("price")                 // decimal, int or long; read on the server so clients never set prices
            currency("currency")            // or fixedCurrency("PHP") / fixedCurrency("USD") / fixedCurrency("USDT")
            description("title")            // optional non-hidden string/enum sent to checkout
            buyerEmail("buyerEmail")        // optional non-hidden string prefilled on hosted checkouts
            payerAddress("wallet")          // optional expected 0x wallet for automatic on-chain attribution
            statusField("state", paid = "paid", failed = "failed", refunded = "refunded")
        }
    }
}
```

At startup `BaseKtPayments` checks both ledger resources and every `payable { }` block at once, refusing to start if a
ledger is writable or if a `payable` field is missing, hidden, of the wrong type, or names a status value outside an
enum's allowed values.

## Installing `BaseKtPayments`

Register one or more `PaymentProvider` instances in `install(BaseKtPayments)` after `install(BaseKt)`:

```kotlin
fun Application.module() {
    val paymongoCfg = environment.config.config("payments.paymongo")
    val paymentwallCfg = environment.config.config("payments.paymentwall")
    val lemonCfg = environment.config.config("payments.lemonsqueezy")
    val kaiaCfg = environment.config.config("payments.kaia")
    val httpClient = HttpClient(CIO)

    install(BaseKt) {
        dataSource = ExposedDataSource(database, appSchema)
        schema = appSchema
        identities = AuthIdentities("jwt")
    }

    install(BaseKtPayments) {
        path = "/payments"                  // or basekt.payments.path in application.conf
        pollEvery = 10.seconds              // optional background scan for incoming on-chain Transfer logs

        provider(
            PayMongoPaymentProvider(
                PayMongoSettings(
                    secretKey = paymongoCfg.property("secretKey").getString(),
                    webhookSecretKey = paymongoCfg.property("webhookSecretKey").getString(),
                    defaultSuccessUrl = paymongoCfg.property("successUrl").getString(),
                    defaultCancelUrl = paymongoCfg.property("cancelUrl").getString(),
                ),
            ),
        )

        provider(
            PaymentwallPaymentProvider(
                PaymentwallSettings(
                    projectKey = paymentwallCfg.property("projectKey").getString(),
                    secretKey = paymentwallCfg.property("secretKey").getString(),
                    testSecretKey = paymentwallCfg.propertyOrNull("testSecretKey")?.getString(),
                    defaultSuccessUrl = paymentwallCfg.property("successUrl").getString(),
                ),
            ),
        )

        provider(
            LemonSqueezyPaymentProvider(
                settings = LemonSqueezySettings(
                    apiKey = lemonCfg.property("apiKey").getString(),
                    storeId = lemonCfg.property("storeId").getString(),
                    webhookSecret = lemonCfg.property("webhookSecret").getString(),
                    defaultVariantId = lemonCfg.property("variantId").getString(),
                ),
                httpClient = httpClient,
            ),
        )

        provider(
            KaiaUsdtPaymentProvider(
                settings = KaiaSettings.mainnet(
                    recipientAddress = kaiaCfg.property("recipientAddress").getString(),
                    rpcUrl = kaiaCfg.propertyOrNull("rpcUrl")?.getString() ?: KaiaNetwork.MAINNET.defaultRpcUrl,
                ),
                httpClient = httpClient,
            ),
            startFromBlock = kaiaCfg.propertyOrNull("startFromBlock")?.getString()?.toLong(),
        )

        onSettled { settlement ->
            log.info("Session ${settlement.session.id} moved ${settlement.previousStatus} -> ${settlement.session.status}")
        }
    }
}
```

Scalar settings can also come from `basekt.payments` in `application.conf`:

```hocon
basekt.payments {
    path = "/payments"
    resource = "payments"
    receiptsResource = "paymentReceipts"
    sessionTtl = "PT30M"
    pollEvery = "PT10S"
}
```

## HTTP endpoints

Under `path` (`/payments` by default), `BaseKtPayments` serves:

| Route | Access | What it does |
|---|---|---|
| `GET /payments/providers` | everyone | lists each provider's id, supported currencies and capabilities (`webhook`, `onChain`, `refundable`, `payout`) |
| `POST /payments/{resource}/{recordId}` | `createAccess` (`everyone`) | reads the record's amount and currency on the server, opens a provider session, stores it in `payments` and returns `201 Created` |
| `GET /payments/sessions/{id}` | `readAccess` (`everyone`) | returns the session's current status (`pending`, `succeeded`, `failed`, `expired`, `refunded`), `checkoutUrl` or `onChain` transfer details |
| `POST /payments/sessions/{id}/confirm` | `createAccess` (`everyone`) | verifies `{"txHash":"0x..."}` on-chain, records `<chainId>:<txHash>:<logIndex>` in `paymentReceipts` and settles the session |
| `POST /payments/sessions/{id}/refund` | `refundAccess` (`admin`) | refunds a `succeeded` session through a `Refundable` provider and updates both ledger and target record |
| `GET`, `POST /payments/webhooks/{provider}` | signed by provider | verifies the signature (and sender IP allowlist when applicable), deduplicates `(provider, eventKey)` in `paymentReceipts`, settles the matching session, and returns JSON or the provider's plain-text acknowledgement (`"OK"` for Paymentwall) |

All error responses use BaseKt's standard `ApiError` JSON envelope (`bad_request`, `unauthorized`, `forbidden`,
`not_found`, `conflict`, `validation_failed`, `rejected`, `not_implemented`, `unavailable`).

## PayMongo (`basekt-payment-paymongo`)

`PayMongoPaymentProvider` wraps [`paymongo-kotlin`](https://github.com/ronjunevaldoz/KotlinPaymongo) without pulling
Ktor server dependencies into the adapter:

- **Flows:** `PayMongoFlow.CHECKOUT_SESSION_V2` (default, `/v2/checkout_sessions`), `CHECKOUT_SESSION_V1`
  (`/v1/checkout_sessions`), or `PAYMENT_LINK` (`/v1/payment_links`).
- **Checkout methods:** `listOf("gcash", "paymaya", "qrph", "card")` by default. Note PayMongo's wire naming: Checkout
  Sessions require `"paymaya"` rather than `"maya"` and do not accept `"atome"`; `PayMongoSettings` validates method
  names eagerly at startup.
- **Webhook verification:** verifies `Paymongo-Signature` (`t=<epoch>,te=<testHex>,li=<liveHex>`) over the exact raw
  request body using constant-time HMAC-SHA256 and enforces `signatureToleranceSeconds` (`300s` by default).
- **Refunds:** implements `Refundable` over PayMongo's refunds API (`payment_id` captured from webhook settlement or
  looked up from the session/link).

## Paymentwall (`basekt-payment-paymentwall`)

`PaymentwallPaymentProvider` builds signed Digital Goods and Subscription widget URLs locally and verifies incoming
pingbacks:

- **Signature v3 widget URLs:** signs sorted parameters with `SHA-256` (`sign_version=3`) against `/api/ps`
  (`ag_type=fixed`) or `/api/subscription` (`ag_type=subscription`, `ag_period_length`, `ag_period_type`,
  `ag_recurring=1`) — no outbound API call is needed to open a session.
- **Sender IP allowlist:** verifies `remoteIp` against Paymentwall's documented pingback sender IPs (`174.36.92.186`,
  `174.36.96.66`, `174.36.92.187`, `174.36.92.192`, `174.37.14.28`, `216.127.71.0/24`) by default and rejects other
  senders with `403 forbidden`. Behind a reverse proxy, install Ktor's `ForwardedHeaders` / `XForwardedHeaders` plugin
  or customize `clientIp` in `install(BaseKtPayments)`.
- **Pingbacks & plain-text `"OK"`:** accepts both `GET` query parameters and `POST` `application/x-www-form-urlencoded`
  bodies, preserves empty `key=` pairs so Paymentwall's dashboard "Test pingback" button succeeds, isolates `is_test=1`
  from live sessions, maps regular/goodwill/risk/chargeback/subscription codes (`0`, `1`, `2`, `12`, `13`, `14`, `200`,
  `201`, `202`, `203`), and replies with `200 OK` plain-text `"OK"`.

## Lemon Squeezy (`basekt-payment-lemonsqueezy`)

`LemonSqueezyPaymentProvider` talks to Lemon Squeezy's JSON:API (`application/vnd.api+json`) over Ktor `HttpClient`:

- **Checkouts:** `POST /v1/checkouts` sends `reference_id` in `checkout_data.custom`, optional buyer `email`, redirect
  URL, and `custom_price` in minor units (`sendCustomPrice = true` by default; set `false` to use a variant's fixed
  catalog or subscription price).
- **Customer portal:** `provider.customerPortalUrl(subscriptionId)` fetches `data.attributes.urls.customer_portal` from
  `GET /v1/subscriptions/{id}` so subscribers can manage billing Methods or cancel renewals.
- **Webhook verification:** verifies `X-Signature` hex HMAC-SHA256 in constant time, ignores webhooks whose `store_id`
  or `test_mode` differs from `LemonSqueezySettings`, and maps `order_created`, `order_refunded`, `subscription_*` and
  `subscription_payment_*` events.

## Kaia USDT (`basekt-payment-kaia`)

`KaiaUsdtPaymentProvider` talks directly to Kaia JSON-RPC over Ktor `HttpClient` — no `web3j` dependency, shareable
with the rest of your Ktor outbound clients, and exact `BigInteger` token math throughout:

- **Network presets:**
  - `KaiaSettings.mainnet(recipientAddress = "0x...")` targets Kaia Mainnet (`chainId = 8217`,
    `https://public-en.node.kaia.io`) and official 6-decimal Tether USD
    (`0xd077a400968890eacc75cdc901f0356c943e4fdb`).
  - `KaiaSettings.kairos(recipientAddress = "0x...", tokenAddress = "0x...")` targets Kaia Kairos Testnet
    (`chainId = 1001`, `https://public-en-kairos.node.kaia.io`).
- **Inbound checkout:** `POST /payments/{resource}/{recordId}` with `{"provider":"kaia-usdt"}` returns an `onChain`
  instruction containing `chainId`, `tokenAddress`, `recipientAddress`, `amountRaw` (`6` decimals) and an EIP-681 URI
  (`ethereum:0xd077...@8217/transfer?address=0x...&uint256=...`) ready for wallets and QR codes.
- **Strict receipt verification:** `confirmTransaction` checks `eth_chainId`, `receipt.status == 1`, block confirmations
  (`minConfirmations`, `1` by default on Kaia's instant-finality BFT), exact KIP-7/ERC-20 contract address (defeating
  fake-token spoofing), `Transfer` topic, recipient wallet, optional expected payer wallet, and amount.
- **Atomic replay protection:** every confirmed log is recorded in `paymentReceipts` under
  `"<chainId>:<txHash>:<logIndex>"`. Re-submitting the same `txHash` for the *same* session returns `200 OK`
  idempotently; submitting an already-claimed `txHash` for *another* session fails with `409 conflict`.
- **Background reconciliation:** when `pollEvery` is set (or `application.baseKtPayments.reconcileOnChainTransfers()` is
  called), `BaseKtPayments` scans `eth_getLogs` in bounded block chunks (`maxLogBlockSpan = 1000`) and settles pending
  sessions automatically when a buyer transfers tokens from their registered `payerAddress` without calling `/confirm`.

### Outbound Kaia USDT payouts

Supplying a `KaiaTransactionSigner` (backed by KMS, Vault, a fee-delegation service, or an isolated signing key) enables
`PayoutCapable` disbursements via `application.baseKtPayments.payout(...)` and `balance(...)`:

```kotlin
val balance = application.baseKtPayments.balance("kaia-usdt")
val receipt = application.baseKtPayments.payout(
    "kaia-usdt",
    PayoutRequest(
        recipientAddress = "0x...",
        amount = PaymentAmount.ofDecimal("25.00", "USDT"),
        referenceId = "payout-42",
    ),
)
```

Before broadcasting, `KaiaUsdtPaymentProvider` serializes each wallet's balance check, nonce lookup and
`eth_sendRawTransaction` under a coroutine `Mutex`, applies `gasBufferPercent` (`115` = `+15%` buffer on gas price and
estimated gas, falling back to `100_000` gas if `eth_estimateGas` fails), and verifies both `USDT` token balance and
18-decimal native `KAIA` gas balance before signing.

## Writing a custom `PaymentProvider`

Implement `PaymentProvider` from `basekt-payment` alongside whichever optional capability interfaces apply
(`WebhookVerifiable`, `OnChainConfirmable`, `Refundable`, `PayoutCapable`), and subclass `PaymentProviderContract` from
`basekt-testing` in your adapter's test suite:

```kotlin
class MyGatewayContractTest : PaymentProviderContract() {
    override fun createPaymentProvider(): PaymentProvider = MyGatewayProvider(/* ... */)
}
```
