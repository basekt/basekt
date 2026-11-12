package io.github.basekt.discord.link

import io.github.basekt.discord.link.memory.InMemoryLinkedAccounts
import io.github.basekt.discord.link.memory.InMemoryPendingLinkStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private class TestClock(var now: Instant = Instant.parse("2030-01-01T12:00:00Z")) : Clock {
    override fun now(): Instant = now
    fun advance(by: Duration) {
        now += by
    }
}

class AccountLinkerTest {
    private val clock = TestClock()
    private val accounts = InMemoryLinkedAccounts()

    private fun linker(
        newCode: () -> String = { error("unused") },
        maxFailures: Int = 3,
    ) = AccountLinker(
        pending = InMemoryPendingLinkStore(),
        accounts = accounts,
        clock = clock,
        ttl = 1.hours,
        maxFailures = maxFailures,
        failureWindow = 10.minutes,
        newCode = newCode,
    )

    private fun codes(vararg values: String): () -> String {
        val queue = ArrayDeque(values.toList())
        return { queue.removeFirst() }
    }

    @Test
    fun `a club member links a Discord user with an issued code`() = runTest {
        val linker = AccountLinker(InMemoryPendingLinkStore(), accounts, clock)
        val code = linker.issue("member-17")
        assertTrue(Regex("[0-9]{6}").matches(code), "six digits: $code")
        assertEquals(LinkResult.Linked("member-17"), linker.redeem(code, "discord-A"))
        assertEquals("discord-A", accounts.discordIdOf("member-17"))
    }

    @Test
    fun `a shop customer links the same way with another id shape`() = runTest {
        val linker = linker(codes("424242"))
        linker.issue("cust_9f3a")
        assertEquals(LinkResult.Linked("cust_9f3a"), linker.redeem(" 424242 ", "discord-B"))
    }

    @Test
    fun `a code works once`() = runTest {
        val linker = linker(codes("111111"))
        linker.issue("member-1")
        assertEquals(LinkResult.Linked("member-1"), linker.redeem("111111", "discord-A"))
        assertEquals(LinkResult.InvalidCode, linker.redeem("111111", "discord-B"))
    }

    @Test
    fun `an expired code is refused and spent`() = runTest {
        val linker = linker(codes("111111"))
        linker.issue("member-1")
        clock.advance(61.minutes)
        assertEquals(LinkResult.Expired, linker.redeem("111111", "discord-A"))
        assertEquals(LinkResult.InvalidCode, linker.redeem("111111", "discord-A"))
    }

    @Test
    fun `issuing again replaces the old code`() = runTest {
        val linker = linker(codes("111111", "222222"))
        linker.issue("member-1")
        linker.issue("member-1")
        assertEquals(LinkResult.InvalidCode, linker.redeem("111111", "discord-A"))
        assertEquals(LinkResult.Linked("member-1"), linker.redeem("222222", "discord-A"))
    }

    @Test
    fun `a colliding code is replaced with another`() = runTest {
        val linker = linker(codes("111111", "111111", "333333"))
        assertEquals("111111", linker.issue("member-1"))
        assertEquals("333333", linker.issue("member-2"))
    }

    @Test
    fun `an account cannot be linked to a second Discord user`() = runTest {
        val linker = linker(codes("111111", "222222"))
        linker.issue("member-1")
        linker.redeem("111111", "discord-A")
        linker.issue("member-1")
        assertEquals(LinkResult.AccountAlreadyLinked, linker.redeem("222222", "discord-B"))
    }

    @Test
    fun `a Discord user cannot be linked to a second account`() = runTest {
        val linker = linker(codes("111111", "222222"))
        linker.issue("member-1")
        linker.redeem("111111", "discord-A")
        linker.issue("member-2")
        assertEquals(LinkResult.DiscordAlreadyLinked, linker.redeem("222222", "discord-A"))
    }

    @Test
    fun `repeated wrong codes lock a Discord user out until the window passes`() = runTest {
        val linker = linker(codes("111111"), maxFailures = 3)
        linker.issue("member-1")
        repeat(3) { assertEquals(LinkResult.InvalidCode, linker.redeem("999999", "discord-A")) }
        assertEquals(LinkResult.TooManyAttempts, linker.redeem("111111", "discord-A"))
        // Another Discord user is not affected.
        assertEquals(LinkResult.Linked("member-1"), linker.redeem("111111", "discord-B"))
        clock.advance(11.minutes)
        assertNotEquals(LinkResult.TooManyAttempts, linker.redeem("999999", "discord-A"))
    }
}
