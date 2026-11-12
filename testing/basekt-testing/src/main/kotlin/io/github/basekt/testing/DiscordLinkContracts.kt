package io.github.basekt.testing

import io.github.basekt.discord.link.LinkOutcome
import io.github.basekt.discord.link.LinkedAccounts
import io.github.basekt.discord.link.PendingLink
import io.github.basekt.discord.link.PendingLinkStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The contract suite every [PendingLinkStore] must pass. Subclass it in the store's tests and return a fresh, empty
 * store from [createPendingLinkStore].
 */
public abstract class PendingLinkStoreContract {
    /** Returns a fresh, empty store under test. */
    protected abstract fun createPendingLinkStore(): PendingLinkStore

    private val expiresAt = Instant.parse("2030-01-01T00:00:00Z")

    private fun test(block: suspend CoroutineScope.() -> Unit) = runTest(timeout = 2.minutes) { block() }

    /** A stored link is returned by [PendingLinkStore.take] with every field intact. */
    @Test
    public fun putThenTake(): Unit = test {
        val store = createPendingLinkStore()
        val link = PendingLink("account-1", "123456", expiresAt)
        assertTrue(store.put(link))
        assertEquals(link, store.take("123456"))
    }

    /** A code can be taken once; the second take finds nothing. */
    @Test
    public fun takeIsSingleUse(): Unit = test {
        val store = createPendingLinkStore()
        store.put(PendingLink("account-1", "123456", expiresAt))
        assertEquals("account-1", store.take("123456")?.accountId)
        assertNull(store.take("123456"))
    }

    /** An unknown code finds nothing. */
    @Test
    public fun takeUnknownCodeIsNull(): Unit = test {
        assertNull(createPendingLinkStore().take("000000"))
    }

    /** Putting again for the same account replaces its code, so the old one stops working. */
    @Test
    public fun putReplacesTheAccountsPreviousCode(): Unit = test {
        val store = createPendingLinkStore()
        store.put(PendingLink("account-1", "111111", expiresAt))
        assertTrue(store.put(PendingLink("account-1", "222222", expiresAt)))
        assertNull(store.take("111111"))
        assertEquals("account-1", store.take("222222")?.accountId)
    }

    /** A code held by another account is refused and leaves that account's link untouched. */
    @Test
    public fun putRefusesACodeAnotherAccountHolds(): Unit = test {
        val store = createPendingLinkStore()
        store.put(PendingLink("account-1", "123456", expiresAt))
        assertFalse(store.put(PendingLink("account-2", "123456", expiresAt)))
        assertEquals("account-1", store.take("123456")?.accountId)
    }

    /** When many callers take one code at once, exactly one gets it. */
    @Test
    public fun concurrentTakesYieldOneWinner(): Unit = test {
        val store = createPendingLinkStore()
        store.put(PendingLink("account-1", "123456", expiresAt))
        val results = List(32) { async(Dispatchers.Default) { store.take("123456") } }.awaitAll()
        assertEquals(1, results.count { it != null })
    }
}

/**
 * The contract suite every [LinkedAccounts] must pass. Subclass it in the implementation's tests and return a fresh,
 * empty instance from [createLinkedAccounts].
 */
public abstract class LinkedAccountsContract {
    /** Returns a fresh, empty implementation under test. */
    protected abstract fun createLinkedAccounts(): LinkedAccounts

    private fun test(block: suspend CoroutineScope.() -> Unit) = runTest(timeout = 2.minutes) { block() }

    /** A new link can be read from both sides. */
    @Test
    public fun linkThenReadBothWays(): Unit = test {
        val accounts = createLinkedAccounts()
        assertEquals(LinkOutcome.LINKED, accounts.link("account-1", "discord-1"))
        assertEquals("discord-1", accounts.discordIdOf("account-1"))
        assertEquals("account-1", accounts.accountOf("discord-1"))
    }

    /** Nothing is linked until link is called. */
    @Test
    public fun unknownSidesAreNull(): Unit = test {
        val accounts = createLinkedAccounts()
        assertNull(accounts.discordIdOf("account-1"))
        assertNull(accounts.accountOf("discord-1"))
    }

    /** Linking the same pair again is allowed and changes nothing. */
    @Test
    public fun linkingTheSamePairAgainIsLinked(): Unit = test {
        val accounts = createLinkedAccounts()
        accounts.link("account-1", "discord-1")
        assertEquals(LinkOutcome.LINKED, accounts.link("account-1", "discord-1"))
    }

    /** An account linked to one Discord user cannot take another. */
    @Test
    public fun accountCannotTakeASecondDiscordUser(): Unit = test {
        val accounts = createLinkedAccounts()
        accounts.link("account-1", "discord-1")
        assertEquals(LinkOutcome.ACCOUNT_ALREADY_LINKED, accounts.link("account-1", "discord-2"))
        assertEquals("discord-1", accounts.discordIdOf("account-1"))
        assertNull(accounts.accountOf("discord-2"))
    }

    /** A Discord user linked to one account cannot take another. */
    @Test
    public fun discordUserCannotTakeASecondAccount(): Unit = test {
        val accounts = createLinkedAccounts()
        accounts.link("account-1", "discord-1")
        assertEquals(LinkOutcome.DISCORD_ALREADY_LINKED, accounts.link("account-2", "discord-1"))
        assertEquals("account-1", accounts.accountOf("discord-1"))
        assertNull(accounts.discordIdOf("account-2"))
    }

    /** When many accounts race for one Discord user, exactly one wins. */
    @Test
    public fun concurrentLinksForOneDiscordUserYieldOneWinner(): Unit = test {
        val accounts = createLinkedAccounts()
        val outcomes = List(32) { i -> async(Dispatchers.Default) { accounts.link("account-$i", "discord-1") } }.awaitAll()
        assertEquals(1, outcomes.count { it == LinkOutcome.LINKED })
    }
}
