package io.github.basekt.discord.link

/**
 * Keeps the codes [AccountLinker] has issued and not yet redeemed. Every function is safe to call concurrently, and
 * implementations must make [take] atomic so one code can never be redeemed twice. Run `PendingLinkStoreContract` from
 * `basekt-testing` against an implementation.
 */
public interface PendingLinkStore {
    /**
     * Stores [link], replacing any pending link for the same account, and returns `true`. Returns `false` and stores
     * nothing when another account already holds the same code, so the caller can pick a new one.
     */
    public suspend fun put(link: PendingLink): Boolean

    /** Atomically removes and returns the pending link with [code], or returns `null` when there is none. */
    public suspend fun take(code: String): PendingLink?
}

/**
 * Keeps which Discord user belongs to which account. A link is one to one: an account has at most one Discord user and a
 * Discord user at most one account. Every function is safe to call concurrently. Run `LinkedAccountsContract` from
 * `basekt-testing` against an implementation.
 */
public interface LinkedAccounts {
    /** The Discord user linked to [accountId], or `null`. */
    public suspend fun discordIdOf(accountId: String): String?

    /** The account linked to [discordId], or `null`. */
    public suspend fun accountOf(discordId: String): String?

    /**
     * Links [accountId] and [discordId] in one atomic step, and reports what happened. Linking a pair that is already
     * linked to each other is [LinkOutcome.LINKED]; linking either side to someone else changes nothing.
     */
    public suspend fun link(accountId: String, discordId: String): LinkOutcome
}
