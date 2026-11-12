package io.github.basekt.discord

/** One slash command as Discord is told about it. */
public data class CommandSpec(
    /** The command's name. */
    public val name: String,
    /** The command's description. */
    public val description: String,
    /** The command's options. */
    public val options: List<CommandOption>,
    /** Whether answers are visible only to the user who ran the command. */
    public val ephemeral: Boolean,
)

/** A user running a slash command, as a [DiscordGateway] reports it. */
public class GatewayInteraction(
    /** The name of the command that was run. */
    public val command: String,
    /** The Discord user id of whoever ran it. */
    public val userId: String,
    /** The options the user gave, by name, as text. */
    public val options: Map<String, String>,
    /** Sends the answer to the user. The gateway makes sure the answer reaches Discord in time. */
    public val respond: suspend (String) -> Unit,
)

/**
 * The connection to Discord. [BaseKtDiscord] uses a Kord-based one by default; tests, or an app that talks to Discord
 * another way, pass their own with [BaseKtDiscordConfig.gateway].
 */
public interface DiscordGateway {
    /**
     * Connects, registers [commands] with Discord, and calls [onInteraction] for every command a user runs, until [stop]
     * is called. This function stays running while the bot is connected.
     */
    public suspend fun run(commands: List<CommandSpec>, onInteraction: suspend (GatewayInteraction) -> Unit)

    /** Disconnects. Safe to call when [run] is not running, and safe to call twice. */
    public suspend fun stop()
}
