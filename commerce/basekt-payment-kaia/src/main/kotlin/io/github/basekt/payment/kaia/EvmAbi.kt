package io.github.basekt.payment.kaia

import java.math.BigInteger

/**
 * Pure Kotlin EVM / Kaia address, hex quantity and ERC-20 / KIP-7 ABI helpers.
 */
public object EvmAbi {
    /** Official Tether USD (`USDT`, 6 decimals) contract on Kaia Mainnet (`8217`). */
    public const val KAIA_MAINNET_USDT: String = "0xd077a400968890eacc75cdc901f0356c943e4fdb"

    /** `keccak256("Transfer(address,address,uint256)")`. */
    public const val TRANSFER_EVENT_TOPIC: String =
        "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"

    private const val BALANCE_OF_SELECTOR: String = "0x70a08231"
    private const val TRANSFER_SELECTOR: String = "0xa9059cbb"

    private val ADDRESS_REGEX = Regex("^0x[0-9a-fA-F]{40}$")
    private val TX_HASH_REGEX = Regex("^0x[0-9a-fA-F]{64}$")
    private val HEX_WORD_REGEX = Regex("^[0-9a-fA-F]+$")

    /** Validates and normalizes an EVM `0x` + 40-hex-character address to lowercase. */
    public fun normalizeAddress(address: String, label: String = "address"): String {
        val trimmed = address.trim()
        require(ADDRESS_REGEX.matches(trimmed)) {
            "Invalid EVM $label '$address': expected 0x followed by 40 hex characters."
        }
        return trimmed.lowercase()
    }

    /** Validates and normalizes a 32-byte `0x` + 64-hex-character transaction hash to lowercase, or returns `null`. */
    public fun normalizeTxHashOrNull(txHash: String): String? {
        val trimmed = txHash.trim()
        return if (TX_HASH_REGEX.matches(trimmed)) trimmed.lowercase() else null
    }

    /** Masks a wallet or contract address for safe logging (`0xd077...4fdb`). */
    public fun maskAddress(address: String?): String {
        if (address.isNullOrBlank()) return "<none>"
        val trimmed = address.trim()
        return if (trimmed.length <= 12) trimmed else "${trimmed.take(6)}...${trimmed.takeLast(4)}"
    }

    /** Encodes a 20-byte normalized address as a 32-byte `0x`-prefixed ABI topic/word. */
    public fun padAddressTopic(address: String): String {
        val clean = normalizeAddress(address).removePrefix("0x")
        return "0x" + clean.padStart(64, '0')
    }

    /** Encodes `balanceOf(address)` calldata. */
    public fun encodeBalanceOf(walletAddress: String): String {
        val clean = normalizeAddress(walletAddress, "walletAddress").removePrefix("0x")
        return BALANCE_OF_SELECTOR + clean.padStart(64, '0')
    }

    /** Encodes `transfer(address,uint256)` calldata. */
    public fun encodeTransfer(recipientAddress: String, amountRaw: BigInteger): String {
        require(amountRaw >= BigInteger.ZERO) { "ERC-20 transfer amount cannot be negative ($amountRaw)." }
        val recipientHex = normalizeAddress(recipientAddress, "recipientAddress").removePrefix("0x").padStart(64, '0')
        val amountHex = amountRaw.toString(16).also {
            require(it.length <= 64) { "ERC-20 transfer amount overflows uint256." }
        }.padStart(64, '0')
        return TRANSFER_SELECTOR + recipientHex + amountHex
    }

    /** Builds an EIP-681 token transfer URI (`ethereum:<token>@<chainId>/transfer?address=<to>&uint256=<amount>`). */
    public fun eip681TransferUri(
        tokenAddress: String,
        chainId: Long,
        recipientAddress: String,
        amountRaw: BigInteger,
    ): String {
        val token = normalizeAddress(tokenAddress, "tokenAddress")
        val recipient = normalizeAddress(recipientAddress, "recipientAddress")
        return "ethereum:$token@$chainId/transfer?address=$recipient&uint256=$amountRaw"
    }

    /** Parses a JSON-RPC hex quantity or 32-byte ABI word (`"0x..."`) as an unsigned [BigInteger]. */
    public fun parseHexUnsigned(hex: String): BigInteger {
        val stripped = hex.trim().removePrefix("0x").removePrefix("0X")
        if (stripped.isEmpty()) return BigInteger.ZERO
        require(HEX_WORD_REGEX.matches(stripped)) { "Invalid hex value '$hex'." }
        return BigInteger(stripped, 16)
    }

    /** Formats a non-negative [BigInteger] as a JSON-RPC quantity (`"0x..."`, no leading zeros except `"0x0"`). */
    public fun toHexQuantity(value: BigInteger): String {
        require(value >= BigInteger.ZERO) { "Hex quantity must be non-negative ($value)." }
        return "0x" + value.toString(16)
    }

    /** Decoded ERC-20 `Transfer(address indexed from, address indexed to, uint256 value)` event. */
    public data class DecodedTransfer(
        public val fromAddress: String,
        public val toAddress: String,
        public val amountRaw: BigInteger,
    )

    /**
     * Decodes an ERC-20 / KIP-7 `Transfer` log if [topics] and [data] match `Transfer(address,address,uint256)`, or
     * returns `null` when the log is another event.
     */
    public fun decodeTransferLog(topics: List<String>, data: String): DecodedTransfer? {
        if (topics.size < 3) return null
        if (!topics[0].equals(TRANSFER_EVENT_TOPIC, ignoreCase = true)) return null
        val from = extractAddressFromTopic(topics[1]) ?: return null
        val to = extractAddressFromTopic(topics[2]) ?: return null
        val value = runCatching { parseHexUnsigned(data) }.getOrNull() ?: return null
        return DecodedTransfer(fromAddress = from, toAddress = to, amountRaw = value)
    }

    private fun extractAddressFromTopic(topic: String): String? {
        val clean = topic.trim().removePrefix("0x").removePrefix("0X")
        if (clean.length != 64 || !HEX_WORD_REGEX.matches(clean)) return null
        return "0x" + clean.takeLast(40).lowercase()
    }
}
