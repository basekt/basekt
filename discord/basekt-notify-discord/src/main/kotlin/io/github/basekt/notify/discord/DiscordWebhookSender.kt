package io.github.basekt.notify.discord

import io.github.basekt.notify.ChannelSender
import io.github.basekt.notify.NotifyMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** True for a Discord channel webhook URL (`https://discord.com/api/webhooks/<id>/<token>`). */
public fun isDiscordWebhookUrl(url: String): Boolean = WEBHOOK_URL.matches(url)

/** Thrown when Discord refuses or can't be reached. The message never contains the webhook URL. */
public class DiscordSendException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Posts to Discord channel webhooks, one per destination name: `DiscordWebhookSender(mapOf("announcements" to url))`.
 * The URLs are secrets that belong in the application's configuration or secret store; staff pick a destination by name
 * in the settings, so no URL is ever stored in a record. Every URL is checked when the sender is built, and none appears
 * in [toString], an exception message or a log line.
 *
 * Nothing is mentioned unless a route names a role, and then only that role: `@everyone`, `@here` and user mentions in
 * the text are switched off.
 */
public class DiscordWebhookSender(
    webhooks: Map<String, String>,
    private val client: HttpClient = defaultClient(),
    private val username: String? = null,
) : ChannelSender {
    private val urls: Map<String, String> = webhooks.toMap()

    init {
        require(urls.isNotEmpty()) { "DiscordWebhookSender needs at least one webhook." }
        for ((name, url) in urls) {
            require(DESTINATION.matches(name)) { "The destination name '$name' must be 1 to 64 letters, digits, '-' or '_'." }
            require(isDiscordWebhookUrl(url)) { "The webhook for '$name' is not a Discord webhook URL (https://discord.com/api/webhooks/<id>/<token>)." }
        }
    }

    override val destinations: Set<String> get() = urls.keys

    override fun mentionProblem(mention: String): String? =
        if (ROLE_ID.matches(mention)) null else "A mention must be a Discord role id: 17 to 20 digits."

    override suspend fun send(destination: String, message: NotifyMessage) {
        val url = urls[destination] ?: throw DiscordSendException("No webhook for the destination '$destination'.")
        val mention = message.mention?.takeIf { ROLE_ID.matches(it) }
        val payload = WebhookPayload(
            content = (mention?.let { "<@&$it> " }.orEmpty() + message.text).take(MAX_CONTENT),
            username = username,
            allowedMentions = AllowedMentions(roles = listOfNotNull(mention)),
        )
        val response = try {
            client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(WebhookPayload.serializer(), payload))
            }
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException && failure !is kotlinx.coroutines.TimeoutCancellationException) throw failure
            throw DiscordSendException("Discord can't be reached for '$destination': ${failure::class.simpleName}")
        }
        if (!response.status.isSuccess()) throw DiscordSendException("Discord answered ${response.status.value} for '$destination'.")
    }

    override fun toString(): String = "DiscordWebhookSender(destinations=${urls.keys.sorted()})"

    /** The defaults for the sender's HTTP client. */
    public companion object {
        private val json = Json { encodeDefaults = false; explicitNulls = false }

        /** An HTTP client with short timeouts, for the sender. */
        public fun defaultClient(): HttpClient = HttpClient(CIO) {
            expectSuccess = false
            defaultRequest { header(HttpHeaders.UserAgent, "basekt-notify") }
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                requestTimeoutMillis = 10_000
            }
        }
    }
}

@Serializable
private class AllowedMentions(
    /** Always sent, so Discord never parses mentions out of the text. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault val parse: List<String> = emptyList(),
    val roles: List<String> = emptyList(),
)

@Serializable
private class WebhookPayload(
    val content: String,
    val username: String? = null,
    @kotlinx.serialization.SerialName("allowed_mentions") val allowedMentions: AllowedMentions,
)

private const val MAX_CONTENT = 2000
private val WEBHOOK_URL = Regex("""https://(?:canary\.|ptb\.)?discord(?:app)?\.com/api/webhooks/\d+/[A-Za-z0-9_-]+""")
private val DESTINATION = Regex("[A-Za-z0-9_-]{1,64}")
private val ROLE_ID = Regex("\\d{17,20}")
