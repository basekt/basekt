package io.github.basekt.discord.link

import kotlin.time.Instant

/** A one-time [code] that lets the Discord user who sends it link to [accountId], until [expiresAt]. */
public data class PendingLink(
    /** Your app's id for the account the code was issued to. */
    public val accountId: String,
    /** The one-time code the account's owner types into Discord. */
    public val code: String,
    /** The moment after which the code is no longer valid. */
    public val expiresAt: Instant,
)

/** What [LinkedAccounts.link] did. */
public enum class LinkOutcome {
    /** The account and the Discord user are now linked, or already were with each other. */
    LINKED,

    /** The account is already linked to a different Discord user. */
    ACCOUNT_ALREADY_LINKED,

    /** The Discord user is already linked to a different account. */
    DISCORD_ALREADY_LINKED,
}

/** What [AccountLinker.redeem] did. */
public sealed interface LinkResult {
    /** The Discord user is now linked to [accountId]. */
    public data class Linked(public val accountId: String) : LinkResult

    /** No pending link has this code: it was never issued, was already used, or was replaced. */
    public data object InvalidCode : LinkResult

    /** The code was valid but has expired. It is gone; the account owner must ask for a new one. */
    public data object Expired : LinkResult

    /** The account is already linked to a different Discord user. */
    public data object AccountAlreadyLinked : LinkResult

    /** The Discord user is already linked to a different account. */
    public data object DiscordAlreadyLinked : LinkResult

    /** This Discord user sent too many wrong codes recently and must wait before trying again. */
    public data object TooManyAttempts : LinkResult
}
