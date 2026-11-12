package io.github.basekt.discord

/** The kind of value a command option takes. */
public enum class OptionType {
    /** Text. */
    STRING,

    /** A whole number. */
    INTEGER,
}

/** One option of a slash command, shown to the user as they type it. */
public data class CommandOption(
    /** Lowercase name, 1 to 32 characters of letters, digits, `-` and `_`. */
    public val name: String,
    /** What the option is for, 1 to 100 characters. */
    public val description: String,
    /** What kind of value it takes. */
    public val type: OptionType = OptionType.STRING,
    /** Whether the user must give it. */
    public val required: Boolean = false,
)

/** What a handler sees and can answer with when a user runs a command. */
public class CommandContext internal constructor(
    /** The name of the command that was run. */
    public val command: String,
    /** The Discord user id of whoever ran it. */
    public val userId: String,
    /** The options the user gave, by name, as text. Options left out are missing. */
    public val options: Map<String, String>,
    private val responder: suspend (String) -> Unit,
) {
    /** Sends [text] as the command's answer. Call it once; the first call is the answer and later calls are ignored. */
    public suspend fun reply(text: String) {
        responder(text)
    }

    /** The text of option [name], or `null` when the user left it out. */
    public fun option(name: String): String? = options[name]
}

/** A slash command. Build one with [BaseKtDiscordConfig.command]. */
public class BotCommand internal constructor(
    /** The command's name as users type it after `/`. */
    public val name: String,
    /** What the command does, shown in Discord's command picker. */
    public val description: String,
    /** The options it takes, in order. */
    public val options: List<CommandOption>,
    /** Whether the answer is visible only to the user who ran the command. */
    public val ephemeral: Boolean,
    internal val handler: suspend (CommandContext) -> Unit,
)

/** Builds one command inside [BaseKtDiscordConfig.command]. */
public class CommandBuilder internal constructor(private val name: String, private val description: String) {
    private val options = mutableListOf<CommandOption>()
    private var handler: (suspend (CommandContext) -> Unit)? = null

    /** Whether the answer is visible only to the user who ran the command. `true` by default. */
    public var ephemeral: Boolean = true

    /** Adds a text option. */
    public fun stringOption(name: String, description: String, required: Boolean = false) {
        options += CommandOption(name, description, OptionType.STRING, required)
    }

    /** Adds a whole-number option. */
    public fun integerOption(name: String, description: String, required: Boolean = false) {
        options += CommandOption(name, description, OptionType.INTEGER, required)
    }

    /** Sets what runs when a user runs the command. */
    public fun handle(handler: suspend (CommandContext) -> Unit) {
        this.handler = handler
    }

    internal fun build(): BotCommand = BotCommand(
        name = name,
        description = description,
        options = options.toList(),
        ephemeral = ephemeral,
        handler = handler ?: { },
    )

    internal fun hasHandler(): Boolean = handler != null
}

private val NAME = Regex("[a-z0-9_-]{1,32}")

/** The problems with [command] as a Discord slash command, in plain words. Empty when it is valid. */
internal fun commandProblems(command: BotCommand, hasHandler: Boolean): List<String> = buildList {
    if (!NAME.matches(command.name)) add("command '${command.name}': the name must be 1 to 32 lowercase letters, digits, '-' or '_'")
    if (command.description.length !in 1..100) add("command '${command.name}': the description must be 1 to 100 characters")
    if (!hasHandler) add("command '${command.name}': it has no handle { }")
    if (command.options.size > 25) add("command '${command.name}': at most 25 options")
    val seen = mutableSetOf<String>()
    for (option in command.options) {
        if (!NAME.matches(option.name)) add("command '${command.name}', option '${option.name}': the name must be 1 to 32 lowercase letters, digits, '-' or '_'")
        if (option.description.length !in 1..100) add("command '${command.name}', option '${option.name}': the description must be 1 to 100 characters")
        if (!seen.add(option.name)) add("command '${command.name}': option '${option.name}' appears twice")
    }
    val firstOptional = command.options.indexOfFirst { !it.required }
    if (firstOptional >= 0 && command.options.drop(firstOptional).any { it.required }) {
        add("command '${command.name}': required options must come before optional ones")
    }
}
