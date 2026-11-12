package io.github.basekt.discord

import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.on
import dev.kord.rest.builder.interaction.integer
import dev.kord.rest.builder.interaction.string

/** The default [DiscordGateway]: a Kord client that registers global slash commands and answers them. */
internal class KordGateway(private val token: String) : DiscordGateway {
    @Volatile
    private var kord: Kord? = null

    override suspend fun run(commands: List<CommandSpec>, onInteraction: suspend (GatewayInteraction) -> Unit) {
        val client = Kord(token)
        kord = client
        val byName = commands.associateBy { it.name }

        // One call replaces every global command, so a removed command disappears and none is registered twice.
        client.createGlobalApplicationCommands {
            for (spec in commands) {
                input(spec.name, spec.description) {
                    for (option in spec.options) {
                        when (option.type) {
                            OptionType.STRING -> string(option.name, option.description) { required = option.required }
                            OptionType.INTEGER -> integer(option.name, option.description) { required = option.required }
                        }
                    }
                }
            }
        }.collect { }

        client.on<ChatInputCommandInteractionCreateEvent> {
            val spec = byName[interaction.command.rootName] ?: return@on
            val response = if (spec.ephemeral) interaction.deferEphemeralResponse() else interaction.deferPublicResponse()
            val options = interaction.command.options.mapValues { (_, argument) -> argument.value.toString() }
            onInteraction(
                GatewayInteraction(
                    command = spec.name,
                    userId = interaction.user.id.toString(),
                    options = options,
                    respond = { text -> response.respond { content = text } },
                ),
            )
        }

        client.login()
    }

    override suspend fun stop() {
        val client = kord ?: return
        kord = null
        client.logout()
        client.shutdown()
    }
}
