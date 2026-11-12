package io.github.basekt.notify.discord

import io.github.basekt.notify.NotifyMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscordWebhookSenderTest {
    private val front = "https://discord.com/api/webhooks/111111111/frontSecret_-token"
    private val back = "https://discord.com/api/webhooks/222222222/backSecret_-token"
    private val role = "123456789012345678"

    private class Capture {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
    }

    private fun sender(capture: Capture, status: HttpStatusCode = HttpStatusCode.NoContent) = DiscordWebhookSender(
        mapOf("front-desk" to front, "back-office" to back),
        client = HttpClient(
            MockEngine { request ->
                capture.urls += request.url.toString()
                capture.bodies += String(request.body.toByteArray())
                if (status.value in 200..299) respond("", status) else respondError(status)
            },
        ),
    )

    @Test
    fun `posts to the named destination without any mention parsing`() = runBlocking {
        val capture = Capture()
        sender(capture).send("back-office", NotifyMessage("Ana enrolled @everyone"))
        assertEquals(listOf(back), capture.urls)
        val body = Json.parseToJsonElement(capture.bodies.single()).jsonObject
        assertEquals("Ana enrolled @everyone", body["content"]!!.jsonPrimitive.content)
        val mentions = body["allowed_mentions"]!!.jsonObject
        assertTrue(mentions["parse"]!!.jsonArray.isEmpty(), "no mention types are parsed from the text")
        assertNull(mentions["roles"])
        assertNull(body["username"])
    }

    @Test
    fun `a role mention pings that role only`() = runBlocking {
        val capture = Capture()
        sender(capture).send("front-desk", NotifyMessage("Order 12 is ready", role))
        val body = Json.parseToJsonElement(capture.bodies.single()).jsonObject
        assertEquals("<@&$role> Order 12 is ready", body["content"]!!.jsonPrimitive.content)
        assertEquals(listOf(role), body["allowed_mentions"]!!.jsonObject["roles"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(body["allowed_mentions"]!!.jsonObject["parse"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `a long message is cut to Discord's limit`() = runBlocking {
        val capture = Capture()
        sender(capture).send("front-desk", NotifyMessage("x".repeat(5000)))
        assertEquals(2000, Json.parseToJsonElement(capture.bodies.single()).jsonObject["content"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun `a refusal or a missing destination throws without the webhook URL`() = runBlocking {
        val refused = assertFailsWith<DiscordSendException> { sender(Capture(), HttpStatusCode.TooManyRequests).send("front-desk", NotifyMessage("x")) }
        assertEquals("Discord answered 429 for 'front-desk'.", refused.message)
        val missing = assertFailsWith<DiscordSendException> { sender(Capture()).send("nowhere", NotifyMessage("x")) }
        assertTrue("nowhere" in missing.message.orEmpty())
        for (message in listOf(refused.message, missing.message)) {
            assertFalse("frontSecret" in message.orEmpty() || "discord.com" in message.orEmpty())
        }
    }

    @Test
    fun `a network failure is reported without the webhook URL`() = runBlocking {
        val failing = DiscordWebhookSender(
            mapOf("front-desk" to front),
            client = HttpClient(MockEngine { throw java.io.IOException("connect to $front failed") }),
        )
        val failure = assertFailsWith<DiscordSendException> { failing.send("front-desk", NotifyMessage("x")) }
        assertFalse("frontSecret" in failure.message.orEmpty())
        assertNotNull(failure.message)
    }

    @Test
    fun `destinations and mentions are checked and the URLs stay out of toString`() {
        val sender = sender(Capture())
        assertEquals(setOf("front-desk", "back-office"), sender.destinations)
        assertNull(sender.mentionProblem(role))
        assertNotNull(sender.mentionProblem("everyone"))
        assertNotNull(sender.mentionProblem("123"))
        assertFalse("Secret" in sender.toString())
    }

    @Test
    fun `bad webhooks are refused when the sender is built`() {
        assertFailsWith<IllegalArgumentException> { DiscordWebhookSender(emptyMap()) }
        assertFailsWith<IllegalArgumentException> { DiscordWebhookSender(mapOf("a" to "https://evil.example.com/api/webhooks/1/x")) }
        assertFailsWith<IllegalArgumentException> { DiscordWebhookSender(mapOf("a" to "http://discord.com/api/webhooks/1/x")) }
        assertFailsWith<IllegalArgumentException> { DiscordWebhookSender(mapOf("bad name" to front)) }
        assertTrue(isDiscordWebhookUrl(front))
    }
}
