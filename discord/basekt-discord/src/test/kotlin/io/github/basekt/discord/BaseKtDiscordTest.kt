package io.github.basekt.discord

import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** A gateway that records what the plugin asked of it and lets a test play users running commands. */
private class FakeGateway : DiscordGateway {
    val started = CompletableDeferred<Unit>()
    val runs = AtomicInteger()
    val stops = AtomicInteger()
    @Volatile var registered: List<CommandSpec> = emptyList()
    @Volatile private var handler: (suspend (GatewayInteraction) -> Unit)? = null
    private val stopped = CompletableDeferred<Unit>()

    override suspend fun run(commands: List<CommandSpec>, onInteraction: suspend (GatewayInteraction) -> Unit) {
        runs.incrementAndGet()
        registered = commands
        handler = onInteraction
        started.complete(Unit)
        stopped.await()
    }

    override suspend fun stop() {
        stops.incrementAndGet()
        stopped.complete(Unit)
    }

    /** Plays [userId] running [command], and returns what the bot answered. */
    suspend fun run(command: String, userId: String = "user-1", options: Map<String, String> = emptyMap()): String? {
        withTimeout(5.seconds) { started.await() }
        var answer: String? = null
        handler!!.invoke(GatewayInteraction(command, userId, options) { answer = it })
        return answer
    }
}

private class TestClock(var now: Instant = Instant.parse("2030-01-01T00:00:00Z")) : Clock {
    override fun now(): Instant = now
}

class BaseKtDiscordTest {
    @Test
    fun `registers the commands and answers them`() = testApplication {
        val gateway = FakeGateway()
        application {
            install(BaseKtDiscord) {
                this.gateway = gateway
                command("echo", "Repeats what you say") {
                    stringOption("text", "What to repeat", required = true)
                    handle { ctx -> ctx.reply("you said ${ctx.option("text")}") }
                }
            }
        }
        startApplication()
        assertEquals("you said hi", gateway.run("echo", options = mapOf("text" to "hi")))
        assertEquals(listOf("echo"), gateway.registered.map { it.name })
        assertEquals("text", gateway.registered.single().options.single().name)
        assertEquals(1, gateway.runs.get())
    }

    @Test
    fun `a club bot answers with its own commands`() = testApplication {
        val gateway = FakeGateway()
        application {
            install(BaseKtDiscord) {
                this.gateway = gateway
                command("roster", "Lists the members") { handle { it.reply("3 members") } }
            }
        }
        startApplication()
        assertEquals("3 members", gateway.run("roster"))
    }

    @Test
    fun `a shop bot uses public answers and number options`() = testApplication {
        val gateway = FakeGateway()
        application {
            install(BaseKtDiscord) {
                this.gateway = gateway
                command("orders", "Your orders") {
                    ephemeral = false
                    integerOption("page", "Page number")
                    handle { ctx -> ctx.reply("page ${ctx.option("page") ?: "1"}") }
                }
            }
        }
        startApplication()
        assertEquals("page 2", gateway.run("orders", options = mapOf("page" to "2")))
        assertEquals("page 1", gateway.run("orders"))
        assertTrue(!gateway.registered.single().ephemeral)
        assertEquals(OptionType.INTEGER, gateway.registered.single().options.single().type)
    }

    @Test
    fun `a handler failure is answered generically and the detail stays out of Discord`() = testApplication {
        val gateway = FakeGateway()
        application {
            install(BaseKtDiscord) {
                this.gateway = gateway
                command("boom", "Always fails") { handle { error("secret database password") } }
            }
        }
        startApplication()
        assertEquals(CommandDispatcher.FAILED, gateway.run("boom"))
    }

    @Test
    fun `a command that never replies still gets an answer`() = testApplication {
        val gateway = FakeGateway()
        application {
            install(BaseKtDiscord) {
                this.gateway = gateway
                command("quiet", "Says nothing") { handle { } }
            }
        }
        startApplication()
        assertEquals(CommandDispatcher.NO_ANSWER, gateway.run("quiet"))
    }

    @Test
    fun `an unknown command is refused`() = testApplication {
        val gateway = FakeGateway()
        application { install(BaseKtDiscord) { this.gateway = gateway; command("a", "A") { handle { it.reply("a") } } } }
        startApplication()
        assertEquals(CommandDispatcher.UNKNOWN_COMMAND, gateway.run("nope"))
    }

    @Test
    fun `each user is rate limited on their own and the window slides`() = testApplication {
        val gateway = FakeGateway()
        val clock = TestClock()
        application {
            install(BaseKtDiscord) {
                this.gateway = gateway
                this.clock = clock
                rateLimit = RateLimit(max = 2, window = 10.seconds)
                command("ping", "Pong") { handle { it.reply("pong") } }
            }
        }
        startApplication()
        assertEquals("pong", gateway.run("ping", "alice"))
        assertEquals("pong", gateway.run("ping", "alice"))
        assertEquals(CommandDispatcher.RATE_LIMITED, gateway.run("ping", "alice"))
        assertEquals("pong", gateway.run("ping", "bob"))
        clock.now += 11.seconds
        assertEquals("pong", gateway.run("ping", "alice"))
    }

    @Test
    fun `a null rate limit turns the limit off`() = testApplication {
        val gateway = FakeGateway()
        application {
            install(BaseKtDiscord) {
                this.gateway = gateway
                rateLimit = null
                command("ping", "Pong") { handle { it.reply("pong") } }
            }
        }
        startApplication()
        repeat(50) { assertEquals("pong", gateway.run("ping")) }
    }

    @Test
    fun `the bot is disconnected exactly once when the application stops`() {
        val gateway = FakeGateway()
        testApplication {
            application { install(BaseKtDiscord) { this.gateway = gateway; command("a", "A") { handle { it.reply("a") } } } }
            startApplication()
            runBlocking { withTimeout(5.seconds) { gateway.started.await() } }
        }
        assertEquals(1, gateway.stops.get())
        assertEquals(1, gateway.runs.get())
    }

    @Test
    fun `a gateway that fails to connect does not stop the application`() = testApplication {
        val failing = object : DiscordGateway {
            val tried = CompletableDeferred<Unit>()
            override suspend fun run(commands: List<CommandSpec>, onInteraction: suspend (GatewayInteraction) -> Unit) {
                tried.complete(Unit)
                error("invalid token")
            }
            override suspend fun stop() = Unit
        }
        application { install(BaseKtDiscord) { gateway = failing; command("a", "A") { handle { it.reply("a") } } } }
        startApplication()
        withTimeout(5.seconds) { failing.tried.await() }
        delay(50)
    }

    @Test
    fun `every configuration problem is listed at once`() {
        val failure = assertFailsWith<Exception> {
            testApplication {
                application {
                    install(BaseKtDiscord) {
                        command("Bad Name", "ok") { handle { } }
                        command("empty", "") { }
                        command("dup", "x") { handle { } }
                        command("dup", "y") { handle { } }
                    }
                }
                startApplication()
            }
        }
        val message = generateSequence<Throwable>(failure) { it.cause }.mapNotNull { it.message }.joinToString("\n")
        assertTrue("no bot token" in message, message)
        assertTrue("'Bad Name'" in message, message)
        assertTrue("description must be 1 to 100" in message, message)
        assertTrue("no handle" in message, message)
        assertTrue("declared more than once" in message, message)
        assertNotNull(failure)
    }
}
