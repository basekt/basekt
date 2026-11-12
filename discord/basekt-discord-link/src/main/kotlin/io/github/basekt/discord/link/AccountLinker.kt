package io.github.basekt.discord.link

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Ties an account to a Discord user with a one-time code. Your site calls [issue] for a signed-in account and shows the
 * code; the account owner sends it to your bot (a `/verify` command), which calls [redeem] with the Discord user id.
 *
 * Codes are random, single use, expire after [ttl], and a Discord user who sends [maxFailures] wrong codes within
 * [failureWindow] is refused until the window passes. The failure counts are kept in this instance's memory.
 */
public class AccountLinker(
    private val pending: PendingLinkStore,
    private val accounts: LinkedAccounts,
    private val clock: Clock = Clock.System,
    private val ttl: Duration = 24.hours,
    private val maxFailures: Int = 5,
    private val failureWindow: Duration = 10.minutes,
    private val newCode: () -> String = ::secureSixDigitCode,
) {
    init {
        require(ttl.isPositive()) { "ttl must be positive." }
        require(maxFailures >= 1) { "maxFailures must be at least 1." }
        require(failureWindow.isPositive()) { "failureWindow must be positive." }
    }

    private val failures = HashMap<String, MutableList<Instant>>()
    private val failuresLock = Mutex()

    /** Issues a new code for [accountId], replacing any code it had, and returns the code to show its owner. */
    public suspend fun issue(accountId: String): String {
        require(accountId.isNotBlank()) { "accountId must not be blank." }
        val expiresAt = clock.now() + ttl
        repeat(MAX_CODE_ATTEMPTS) {
            val code = newCode()
            if (pending.put(PendingLink(accountId, code, expiresAt))) return code
        }
        error("Could not issue a unique code after $MAX_CODE_ATTEMPTS attempts.")
    }

    /** Redeems [code] for the Discord user [discordId]. */
    public suspend fun redeem(code: String, discordId: String): LinkResult {
        require(discordId.isNotBlank()) { "discordId must not be blank." }
        val now = clock.now()
        if (isLockedOut(discordId, now)) return LinkResult.TooManyAttempts

        val link = pending.take(code.trim())
        if (link == null) {
            recordFailure(discordId, now)
            return LinkResult.InvalidCode
        }
        if (now > link.expiresAt) return LinkResult.Expired

        return when (accounts.link(link.accountId, discordId)) {
            LinkOutcome.LINKED -> LinkResult.Linked(link.accountId)
            LinkOutcome.ACCOUNT_ALREADY_LINKED -> LinkResult.AccountAlreadyLinked
            LinkOutcome.DISCORD_ALREADY_LINKED -> LinkResult.DiscordAlreadyLinked
        }
    }

    private suspend fun isLockedOut(discordId: String, now: Instant): Boolean = failuresLock.withLock {
        val recent = failures[discordId]
        recent?.removeAll { at -> now - at > failureWindow }
        if (recent != null && recent.isEmpty()) failures.remove(discordId)
        (recent?.size ?: 0) >= maxFailures
    }

    private suspend fun recordFailure(discordId: String, now: Instant) {
        failuresLock.withLock { failures.getOrPut(discordId) { ArrayList() }.add(now) }
    }

    private companion object {
        const val MAX_CODE_ATTEMPTS = 10
    }
}

private val secureRandom = SecureRandom()

/** A six-digit code from a cryptographically secure source. */
internal fun secureSixDigitCode(): String = (secureRandom.nextInt(900_000) + 100_000).toString()
