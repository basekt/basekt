package io.github.basekt.payment.kaia

import java.math.BigInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Built-in Kaia network presets.
 *
 * @property chainId EVM chain id (`8217` Mainnet, `1001` Kairos Testnet).
 * @property displayName human-readable network name.
 * @property defaultRpcUrl public Kaia endpoint URL.
 * @property explorerBaseUrl KaiaScan base URL without trailing slash.
 */
public enum class KaiaNetwork(
    public val chainId: Long,
    public val displayName: String,
    public val defaultRpcUrl: String,
    public val explorerBaseUrl: String,
) {
    /** Kaia Mainnet (`chainId = 8217`). */
    MAINNET(
        chainId = 8217L,
        displayName = "Kaia Mainnet",
        defaultRpcUrl = "https://public-en.node.kaia.io",
        explorerBaseUrl = "https://kaiascan.io",
    ),

    /** Kaia Kairos Testnet (`chainId = 1001`). */
    KAIROS(
        chainId = 1001L,
        displayName = "Kaia Kairos Testnet",
        defaultRpcUrl = "https://public-en-kairos.node.kaia.io",
        explorerBaseUrl = "https://kairos.kaiascan.io",
    ),
}

/**
 * An unsigned EVM / Kaia transaction passed to [KaiaTransactionSigner] during outbound [io.github.basekt.payment.PayoutCapable.payout] calls.
 *
 * @property chainId target EVM chain id.
 * @property nonce sender account transaction count.
 * @property gasPrice gas price in wei/peb.
 * @property gasLimit maximum gas units allowed.
 * @property to normalized `0x` contract or recipient address.
 * @property value native KAIA value in peb (`0` for ERC-20 token calls).
 * @property data `0x`-prefixed ABI-encoded calldata.
 */
public data class UnsignedEvmTransaction(
    public val chainId: Long,
    public val nonce: BigInteger,
    public val gasPrice: BigInteger,
    public val gasLimit: BigInteger,
    public val to: String,
    public val value: BigInteger,
    public val data: String,
)

/**
 * Signs an [UnsignedEvmTransaction] (locally, via KMS/Vault, or via a Kaia fee-delegation service) and returns the
 * `0x`-prefixed raw signed transaction hex ready for `eth_sendRawTransaction`.
 */
public interface KaiaTransactionSigner {
    /** The signer's `0x`-prefixed sender wallet address. */
    public val address: String

    /** Signs [transaction] and returns a `0x`-prefixed raw transaction hex payload. */
    public suspend fun sign(transaction: UnsignedEvmTransaction): String
}

/**
 * Settings for [KaiaUsdtPaymentProvider]. Build from `ApplicationConfig` in the host application.
 *
 * @property id provider identifier (`"kaia-usdt"` by default).
 * @property chainId expected EVM chain id (`8217` on Kaia Mainnet).
 * @property networkName human-readable network label.
 * @property rpcUrl HTTP(S) JSON-RPC endpoint URL.
 * @property tokenAddress KIP-7 / ERC-20 token contract address ([EvmAbi.KAIA_MAINNET_USDT] by default).
 * @property tokenSymbol uppercase token ticker (`"USDT"` by default).
 * @property tokenDecimals token decimal places (`6` for USDT).
 * @property recipientAddress merchant wallet address that receives inbound payments.
 * @property explorerBaseUrl optional block-explorer base URL (such as `"https://kaiascan.io"`).
 * @property minConfirmations minimum block confirmations required for inbound transfers (`1` by default on Kaia's instant-finality BFT).
 * @property requireExactAmount when `true` (`true` by default), transfers exceeding `expectedAmount` are also refused unless exact; when `false`, any `amount >= expectedAmount` is accepted.
 * @property maxLogBlockSpan maximum block span per single `eth_getLogs` RPC call (`1000` by default).
 * @property requestTimeout per-RPC HTTP timeout (`15s` by default).
 * @property signer optional [KaiaTransactionSigner] enabling outbound [io.github.basekt.payment.PayoutCapable] transfers.
 * @property gasBufferPercent safety multiplier applied to gas price and estimated gas limit (`115` = `+15%` buffer).
 * @property fallbackGasLimit gas limit used when `eth_estimateGas` fails (`100_000` by default).
 * @property receiptPollInterval interval between receipt polls after broadcasting an outbound payout (`2s` by default).
 * @property receiptTimeout maximum wait time for an outbound payout receipt (`60s` by default).
 */
public data class KaiaSettings(
    public val id: String = "kaia-usdt",
    public val chainId: Long = KaiaNetwork.MAINNET.chainId,
    public val networkName: String = KaiaNetwork.MAINNET.displayName,
    public val rpcUrl: String = KaiaNetwork.MAINNET.defaultRpcUrl,
    public val tokenAddress: String = EvmAbi.KAIA_MAINNET_USDT,
    public val tokenSymbol: String = "USDT",
    public val tokenDecimals: Int = 6,
    public val recipientAddress: String,
    public val explorerBaseUrl: String? = KaiaNetwork.MAINNET.explorerBaseUrl,
    public val minConfirmations: Long = 1L,
    public val requireExactAmount: Boolean = true,
    public val maxLogBlockSpan: Long = 1_000L,
    public val requestTimeout: Duration = 15.seconds,
    public val signer: KaiaTransactionSigner? = null,
    public val gasBufferPercent: Int = 115,
    public val fallbackGasLimit: BigInteger = BigInteger.valueOf(100_000L),
    public val receiptPollInterval: Duration = 2_000.milliseconds,
    public val receiptTimeout: Duration = 60.seconds,
) {
    /** Normalized lowercase token contract address. */
    public val normalizedTokenAddress: String = EvmAbi.normalizeAddress(tokenAddress, "tokenAddress")

    /** Normalized lowercase merchant recipient wallet address. */
    public val normalizedRecipientAddress: String = EvmAbi.normalizeAddress(recipientAddress, "recipientAddress")

    init {
        require(id.isNotBlank()) { "Kaia provider id must not be blank." }
        require(chainId > 0) { "chainId ($chainId) must be positive." }
        require(rpcUrl.startsWith("http://") || rpcUrl.startsWith("https://")) {
            "rpcUrl '$rpcUrl' must start with http:// or https://."
        }
        require(tokenSymbol.isNotBlank()) { "tokenSymbol must not be blank." }
        require(tokenDecimals in 0..36) { "tokenDecimals ($tokenDecimals) must be between 0 and 36." }
        require(minConfirmations >= 1L) { "minConfirmations ($minConfirmations) must be >= 1." }
        require(maxLogBlockSpan >= 1L) { "maxLogBlockSpan ($maxLogBlockSpan) must be >= 1." }
        require(gasBufferPercent >= 100) { "gasBufferPercent ($gasBufferPercent) must be >= 100." }
        require(fallbackGasLimit > BigInteger.ZERO) { "fallbackGasLimit must be positive." }
        signer?.let { EvmAbi.normalizeAddress(it.address, "signer.address") }
    }

    /** Presets for Kaia Mainnet and Kairos Testnet. */
    public companion object {
        /** Creates Kaia Mainnet (`8217`) settings for official Tether USD (`0xd077...4fdb`, 6 decimals). */
        public fun mainnet(
            recipientAddress: String,
            rpcUrl: String = KaiaNetwork.MAINNET.defaultRpcUrl,
            signer: KaiaTransactionSigner? = null,
        ): KaiaSettings = KaiaSettings(
            chainId = KaiaNetwork.MAINNET.chainId,
            networkName = KaiaNetwork.MAINNET.displayName,
            rpcUrl = rpcUrl,
            tokenAddress = EvmAbi.KAIA_MAINNET_USDT,
            recipientAddress = recipientAddress,
            explorerBaseUrl = KaiaNetwork.MAINNET.explorerBaseUrl,
            signer = signer,
        )

        /** Creates Kaia Kairos Testnet (`1001`) settings for a deployed test USDT/KIP-7 [tokenAddress]. */
        public fun kairos(
            recipientAddress: String,
            tokenAddress: String,
            rpcUrl: String = KaiaNetwork.KAIROS.defaultRpcUrl,
            signer: KaiaTransactionSigner? = null,
        ): KaiaSettings = KaiaSettings(
            chainId = KaiaNetwork.KAIROS.chainId,
            networkName = KaiaNetwork.KAIROS.displayName,
            rpcUrl = rpcUrl,
            tokenAddress = tokenAddress,
            recipientAddress = recipientAddress,
            explorerBaseUrl = KaiaNetwork.KAIROS.explorerBaseUrl,
            signer = signer,
        )
    }
}
