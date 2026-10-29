# basekt-payment-kaia

A `PaymentProvider` for Kaia KIP-7 / ERC-20 tokens (by default Tether USD, `0xd077a400968890eacc75cdc901f0356c943e4fdb`, 6 decimals) over Ktor `HttpClient` and standard EVM JSON-RPC 2.0, with no heavy Web3j SDK dependency. Implements `OnChainConfirmable` for inbound checkout and `PayoutCapable` for gas-checked outbound disbursements.

```kotlin
dependencies { implementation("io.github.basekt:basekt-payment-kaia:0.3.0-SNAPSHOT") }
```

In the latest `main`, published as a snapshot until 0.3.0 is released: see [Getting started](https://basekt.github.io/basekt/getting-started/#snapshots).

Builds on: basekt-payment, ktor-client-core.

## Use

```kotlin
val kaiaUsdt = KaiaUsdtPaymentProvider(
    settings = KaiaSettings.mainnet(
        recipientAddress = config.property("kaia.recipientAddress").getString(),
    ),
    httpClient = sharedHttpClient, // optional: omit to create an internal CIO HttpClient
)
```

## Good to know

- Accepts an application-shared Ktor `HttpClient` (including `MockEngine` in unit tests) or creates its own `CIO` client when omitted.
- Verifies `eth_chainId` (`8217` Mainnet, `1001` Kairos), receipt status (`0x1`), confirmation count, exact token contract address (preventing fake token spoofing), recipient address, and transfer amount.
- Produces canonical `<chainId>:<txHash>:<logIndex>` event keys so `BaseKtPayments` rejects cross-order `txHash` replay atomically.
- Outbound payouts (`PayoutCapable`) take a pluggable `KaiaTransactionSigner` and check both token balance and 18-decimal native `KAIA` gas balance (+15% safety buffer) before broadcasting.

## Documentation

- [Payments](https://basekt.github.io/basekt/payments/)
- [All modules](../../README.md#modules)
