package io.github.basekt.discord

import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.log
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.thread
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The configuration of [BaseKtDiscord]. The bot token can come from `basekt.discord.token`; the commands are set in
 * code. The token is a secret: keep it in the application's configuration or secret store, never in source.
 */
public class BaseKtDiscordConfig internal constructor(config: ApplicationConfig) {
    /** The bot token. `basekt.discord.token`; needed unless [gateway] is set. It is never logged. */
    public var token: String? = config.propertyOrNull("token")?.getString()

    /** The most commands one user may run in a window, or `null` for no limit. A limit of 5 per 10 seconds by default. */
    public var rateLimit: RateLimit? = RateLimit()

    /** Replaces the Kord connection, for tests or an app that talks to Discord another way. */
    public var gateway: DiscordGateway? = null

    /** The clock the rate limit uses. [Clock.System] by default. */
    public var clock: Clock = Clock.System

    /** How long the application waits for the bot to disconnect when it stops. 10 seconds by default. */
    public var shutdownTimeout: Duration = 10.seconds

    internal val commands = mutableListOf<Pair<BotCommand, Boolean>>()

    /**
     * Adds the slash command [name] (`/name`), described to users by [description]:
     *
     * ```kotlin
     * command("verify", "Link your account") {
     *     stringOption("code", "The code from the site", required = true)
     *     handle { ctx -> ctx.reply("Checking ${ctx.option("code")}") }
     * }
     * ```
     */
    public fun command(name: String, description: String, build: CommandBuilder.() -> Unit) {
        val builder = CommandBuilder(name, description).apply(build)
        commands += builder.build() to builder.hasHandler()
    }
}

/**
 * Runs a Discord bot beside the Ktor application: it connects when the application starts, registers the configured
 * slash commands, answers them, and disconnects when the application stops. A failure to connect is logged and does not
 * stop the application.
 *
 * Every configuration problem is reported at install, all at once. Each user is limited to [BaseKtDiscordConfig.rateLimit]
 * commands per window, and an exception in a handler is logged and answered with a generic message, so no internal
 * detail reaches Discord.
 */
public val BaseKtDiscord: ApplicationPlugin<BaseKtDiscordConfig> = createApplicationPlugin(
    name = "BaseKtDiscord",
    configurationPath = "basekt.discord",
    createConfiguration = ::BaseKtDiscordConfig,
) {
    val config = pluginConfig
    val problems = buildList {
        if (config.gateway == null && config.token.isNullOrBlank()) {
            add("no bot token: set basekt.discord.token or pass a gateway")
        }
        for ((command, hasHandler) in config.commands) addAll(commandProblems(command, hasHandler))
        config.commands.groupBy { it.first.name }.filterValues { it.size > 1 }.keys.forEach {
            add("command '$it' is declared more than once")
        }
    }
    require(problems.isEmpty()) { "The Discord bot is not configured correctly:\n" + problems.joinToString("\n") { "- $it" } }

    val commands = config.commands.map { it.first }
    val specs = commands.map { CommandSpec(it.name, it.description, it.options, it.ephemeral) }
    val gateway = config.gateway ?: KordGateway(requireNotNull(config.token))
    val log = application.log
    val dispatcher = CommandDispatcher(
        commands = commands,
        limiter = config.rateLimit?.let { SlidingWindowLimiter(it, config.clock) },
        onError = { name, failure -> log.error("Discord command /$name failed", failure) },
    )
    val shutdownTimeout = config.shutdownTimeout

    on(MonitoringEvent(ApplicationStarted)) { app ->
        app.log.info("BaseKt Discord bot starting with ${commands.size} command(s)")
        app.launch {
            try {
                gateway.run(specs, dispatcher::dispatch)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                app.log.error("The Discord bot stopped: ${failure::class.simpleName}", failure)
            }
        }
    }

    on(MonitoringEvent(ApplicationStopping)) { app ->
        // On its own thread, so a saturated coroutine dispatcher cannot deadlock the shutdown.
        val stopper = thread(name = "basekt-discord-stop", isDaemon = true) {
            runBlocking {
                if (withTimeoutOrNull(shutdownTimeout) { gateway.stop() } == null) {
                    app.log.warn("The Discord bot did not disconnect within $shutdownTimeout")
                }
            }
        }
        stopper.join((shutdownTimeout + 1.seconds).inWholeMilliseconds)
    }
}
