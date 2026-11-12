package io.github.basekt.discord

import kotlinx.coroutines.CancellationException

/** Routes each [GatewayInteraction] to its command, applies the rate limit, and keeps handler failures from the user. */
internal class CommandDispatcher(
    commands: List<BotCommand>,
    private val limiter: SlidingWindowLimiter?,
    private val onError: (command: String, failure: Throwable) -> Unit,
) {
    private val byName = commands.associateBy { it.name }

    suspend fun dispatch(interaction: GatewayInteraction) {
        val command = byName[interaction.command]
        if (command == null) {
            interaction.respond(UNKNOWN_COMMAND)
            return
        }
        if (limiter != null && !limiter.tryAcquire(interaction.userId)) {
            interaction.respond(RATE_LIMITED)
            return
        }
        var answered = false
        val context = CommandContext(command.name, interaction.userId, interaction.options) { text ->
            if (!answered) {
                answered = true
                interaction.respond(text)
            }
        }
        try {
            command.handler(context)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            onError(command.name, failure)
            if (!answered) {
                answered = true
                interaction.respond(FAILED)
            }
        }
        if (!answered) interaction.respond(NO_ANSWER)
    }

    internal companion object {
        const val UNKNOWN_COMMAND = "That command isn't available."
        const val RATE_LIMITED = "You're going too fast. Try again in a few seconds."
        const val FAILED = "Something went wrong. Try again later."
        const val NO_ANSWER = "Done."
    }
}
