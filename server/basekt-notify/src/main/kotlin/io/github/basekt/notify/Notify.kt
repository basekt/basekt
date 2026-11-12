package io.github.basekt.notify

import kotlinx.coroutines.CancellationException

/** What to send: the [text], and a role to mention when the event's route names one. */
public data class NotifyMessage(
    /** The message text. */
    public val text: String,
    /** The id of the role to mention, or `null` for no mention. How it is shown is up to the [ChannelSender]. */
    public val mention: String? = null,
)

/** Where one event goes, as the settings record for it says. */
public data class EventRoute(
    /** The event's key, such as `"signup"`. */
    public val event: String,
    /** Whether the event is sent at all. */
    public val enabled: Boolean,
    /** The name of the destination to send it to; one of [ChannelSender.destinations]. */
    public val destination: String,
    /** The id of a role to mention, or `null`. */
    public val mention: String?,
)

/** Finds where an event goes. [ResourceNotifySettings] reads it from a settings resource, which staff edit in the admin. */
public fun interface NotifySettings {
    /** The route for [event], or `null` when the event has none. Read on every send, so a change applies at once. */
    public suspend fun route(event: String): EventRoute?
}

/**
 * Sends a message to a channel. Destinations are names, such as `"announcements"`; what each name points at (a webhook
 * URL, a channel id) is the sender's own configuration, so a secret never has to be stored in a record staff can read.
 */
public interface ChannelSender {
    /** The destination names this sender can send to. Staff choose among these in the settings. */
    public val destinations: Set<String>

    /** Sends [message] to [destination]. Throws on failure; [Notifier] reports it and carries on. */
    public suspend fun send(destination: String, message: NotifyMessage)

    /** What is wrong with [mention] for this channel, or `null` when it is fine. Checked when staff save a route. */
    public fun mentionProblem(mention: String): String? = null
}

/**
 * Sends events to the destinations the settings choose. [publish] never throws: an event with no route, or a disabled
 * one, sends nothing, and a send that fails is reported to [onError] and does not reach the caller.
 */
public class Notifier(
    private val settings: NotifySettings,
    private val sender: ChannelSender,
    private val onError: (event: String, failure: Throwable) -> Unit = { _, _ -> },
) {
    /** Sends [text] for [event] to where its route says, if it has an enabled one. */
    public suspend fun publish(event: String, text: String) {
        try {
            val route = settings.route(event) ?: return
            if (!route.enabled) return
            if (route.destination !in sender.destinations) {
                onError(event, IllegalStateException("The route for '$event' names the destination '${route.destination}', which the sender doesn't have."))
                return
            }
            sender.send(route.destination, NotifyMessage(text, route.mention))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            onError(event, failure)
        }
    }
}
