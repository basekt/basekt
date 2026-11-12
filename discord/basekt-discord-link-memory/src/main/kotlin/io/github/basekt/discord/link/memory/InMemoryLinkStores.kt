package io.github.basekt.discord.link.memory

import io.github.basekt.discord.link.LinkOutcome
import io.github.basekt.discord.link.LinkedAccounts
import io.github.basekt.discord.link.PendingLink
import io.github.basekt.discord.link.PendingLinkStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A [PendingLinkStore] that keeps its links in memory, for tests, samples and prototypes. */
public class InMemoryPendingLinkStore : PendingLinkStore {
    private val byAccount = HashMap<String, PendingLink>()
    private val lock = Mutex()

    override suspend fun put(link: PendingLink): Boolean = lock.withLock {
        val holder = byAccount.values.firstOrNull { it.code == link.code }
        if (holder != null && holder.accountId != link.accountId) return@withLock false
        byAccount[link.accountId] = link
        true
    }

    override suspend fun take(code: String): PendingLink? = lock.withLock {
        val found = byAccount.values.firstOrNull { it.code == code } ?: return@withLock null
        byAccount.remove(found.accountId)
        found
    }
}

/** A [LinkedAccounts] that keeps its links in memory, for tests, samples and prototypes. */
public class InMemoryLinkedAccounts : LinkedAccounts {
    private val discordByAccount = HashMap<String, String>()
    private val accountByDiscord = HashMap<String, String>()
    private val lock = Mutex()

    override suspend fun discordIdOf(accountId: String): String? = lock.withLock { discordByAccount[accountId] }

    override suspend fun accountOf(discordId: String): String? = lock.withLock { accountByDiscord[discordId] }

    override suspend fun link(accountId: String, discordId: String): LinkOutcome = lock.withLock {
        val currentDiscord = discordByAccount[accountId]
        val currentAccount = accountByDiscord[discordId]
        when {
            currentDiscord == discordId && currentAccount == accountId -> LinkOutcome.LINKED
            currentDiscord != null -> LinkOutcome.ACCOUNT_ALREADY_LINKED
            currentAccount != null -> LinkOutcome.DISCORD_ALREADY_LINKED
            else -> {
                discordByAccount[accountId] = discordId
                accountByDiscord[discordId] = accountId
                LinkOutcome.LINKED
            }
        }
    }
}
