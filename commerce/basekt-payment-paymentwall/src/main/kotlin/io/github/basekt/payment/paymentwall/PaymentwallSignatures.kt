package io.github.basekt.payment.paymentwall

import java.security.MessageDigest
import java.util.TreeMap

/**
 * Paymentwall Signature v3 (`SHA-256`) signing and constant-time verification helpers.
 *
 * Paymentwall Signature v3 sorts all request parameters alphabetically by key, omits `sig` and `sign`, concatenates
 * `key=value` pairs using raw unencoded values (preserving empty `key=` pairs), appends `secretKey`, and computes a
 * lowercase hexadecimal `SHA-256` digest.
 */
public object PaymentwallSignatures {
    /** Computes the lowercase hex Paymentwall Signature v3 digest for [parameters] and [secretKey]. */
    public fun signV3(parameters: Map<String, String>, secretKey: String): String {
        val sorted = TreeMap(parameters).apply {
            remove("sig")
            remove("sign")
        }
        val baseString = buildString {
            for ((key, value) in sorted) {
                append(key)
                append('=')
                append(value)
            }
            append(secretKey)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(baseString.encodeToByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Returns `true` when [parameters] declares `sign_version=3` and carries a `sign` value matching [secretKey] in
     * constant time.
     */
    public fun verifyV3(parameters: Map<String, String>, secretKey: String): Boolean {
        if (parameters["sign_version"] != "3") return false
        val provided = parameters["sign"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        val expected = signV3(parameters, secretKey)
        return MessageDigest.isEqual(expected.encodeToByteArray(), provided.encodeToByteArray())
    }
}

/**
 * Paymentwall's documented pingback sender IPv4 allowlist:
 * - `174.36.92.186`
 * - `174.36.96.66`
 * - `174.36.92.187`
 * - `174.36.92.192`
 * - `174.37.14.28`
 * - `216.127.71.0/24`
 */
public object PaymentwallIpAllowlist {
    /** Exact Paymentwall pingback sender IPv4 addresses outside `216.127.71.0/24`. */
    public val EXACT_IPS: Set<String> = setOf(
        "174.36.92.186",
        "174.36.96.66",
        "174.36.92.187",
        "174.36.92.192",
        "174.37.14.28",
    )

    /** Returns `true` if [ipAddress] belongs to Paymentwall's documented pingback sender IP allowlist. */
    public fun isAllowed(ipAddress: String): Boolean {
        val normalized = ipAddress.trim().removePrefix("::ffff:").removePrefix("::FFFF:")
        if (normalized in EXACT_IPS) return true
        val parts = normalized.split('.')
        if (parts.size != 4) return false
        val octets = IntArray(4)
        for (i in 0..3) {
            val part = parts[i]
            if (part.isEmpty() || part.any { !it.isDigit() }) return false
            val value = part.toIntOrNull() ?: return false
            if (value !in 0..255) return false
            octets[i] = value
        }
        return octets[0] == 216 && octets[1] == 127 && octets[2] == 71
    }
}
