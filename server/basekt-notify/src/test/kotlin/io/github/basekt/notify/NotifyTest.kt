package io.github.basekt.notify

import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.schema
import io.github.basekt.server.BaseKt
import io.github.basekt.server.Identity
import io.github.basekt.server.IdentityResolver
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A sender that records what it was asked to send, and can be told to fail. */
private class FakeSender(override val destinations: Set<String>, var failWith: Throwable? = null) : ChannelSender {
    val sent = mutableListOf<Pair<String, NotifyMessage>>()

    override suspend fun send(destination: String, message: NotifyMessage) {
        failWith?.let { throw it }
        sent += destination to message
    }

    override fun mentionProblem(mention: String): String? = if (mention.all(Char::isDigit)) null else "A mention must be digits."
}

/** A made-up school: enrolment and results events, edited by the office. */
private val schoolSchema: Schema = schema {
    notifyRoutes(editors = setOf("office"))
}

/** A made-up bakery: its own routes resource name, edited by the owner. */
private val bakerySchema: Schema = schema {
    notifyRoutes("bakeRoutes", editors = setOf("owner"))
}

private val headerIdentities = IdentityResolver { call ->
    call.request.headers["X-User"]?.let { Identity(it, call.request.headers["X-Roles"].orEmpty().split(',').filter(String::isNotBlank).toSet()) }
}

private fun HttpRequestBuilder.caller(user: String, vararg roles: String) {
    header("X-User", user)
    header("X-Roles", roles.joinToString(","))
}

private fun HttpRequestBuilder.json(text: String) {
    contentType(ContentType.Application.Json)
    setBody(text)
}

private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject

class NotifyTest {
    private val errors = mutableListOf<Pair<String, Throwable>>()

    private fun app(
        schema: Schema,
        sender: FakeSender,
        resource: String? = null,
        test: suspend ApplicationTestBuilder.(Notifier) -> Unit,
    ) = testApplication {
        var notifier: Notifier? = null
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                this.schema = schema
                identities = headerIdentities
            }
            install(BaseKtNotify) {
                this.sender = sender
                if (resource != null) this.resource = resource
                onError = { event, failure -> errors += event to failure }
            }
            notifier = this.notifier
        }
        startApplication()
        test(notifier!!)
    }

    @Test
    fun `an event with no route sends nothing`() = runBlocking {
        val sender = FakeSender(setOf("main"))
        Notifier({ null }, sender).publish("enrolled", "Ana enrolled")
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `a disabled route sends nothing and an enabled one sends with its mention`() = runBlocking {
        val sender = FakeSender(setOf("main"))
        val off = Notifier({ EventRoute(it, false, "main", null) }, sender)
        off.publish("enrolled", "x")
        assertTrue(sender.sent.isEmpty())

        val on = Notifier({ EventRoute(it, true, "main", "123") }, sender)
        on.publish("enrolled", "Ana enrolled")
        assertEquals(listOf("main" to NotifyMessage("Ana enrolled", "123")), sender.sent)
    }

    @Test
    fun `a failing send is reported and never reaches the caller`() = runBlocking {
        val sender = FakeSender(setOf("main"), failWith = IllegalStateException("boom"))
        val seen = mutableListOf<String>()
        Notifier({ EventRoute(it, true, "main", null) }, sender) { event, _ -> seen += event }.publish("enrolled", "x")
        assertEquals(listOf("enrolled"), seen)
    }

    @Test
    fun `a route naming a missing destination is reported and sends nothing`() = runBlocking {
        val sender = FakeSender(setOf("main"))
        val seen = mutableListOf<Throwable>()
        Notifier({ EventRoute(it, true, "gone", null) }, sender) { _, failure -> seen += failure }.publish("enrolled", "x")
        assertTrue(sender.sent.isEmpty())
        assertTrue("gone" in seen.single().message.orEmpty())
    }

    @Test
    fun `a school's office routes an event, changes it, and turns it off without a restart`() {
        val sender = FakeSender(setOf("front-desk", "staff-room"))
        app(schoolSchema, sender) { notifier ->
            val created = client.post("/api/notifyRoutes") {
                caller("clerk", "office")
                json("""{"id":"r1","event":"enrolled","enabled":true,"destination":"front-desk"}""")
            }
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())

            notifier.publish("enrolled", "Ana enrolled")
            assertEquals(listOf("front-desk"), sender.sent.map { it.first })

            client.patch("/api/notifyRoutes/r1") { caller("clerk", "office"); json("""{"destination":"staff-room","mention":"987654321"}""") }
            notifier.publish("enrolled", "Ben enrolled")
            assertEquals("staff-room", sender.sent.last().first)
            assertEquals("987654321", sender.sent.last().second.mention)

            client.patch("/api/notifyRoutes/r1") { caller("clerk", "office"); json("""{"enabled":false}""") }
            notifier.publish("enrolled", "Cy enrolled")
            assertEquals(2, sender.sent.size)
            assertTrue(errors.isEmpty(), errors.toString())
        }
    }

    @Test
    fun `a bakery uses its own resource name and an unknown destination is refused when saved`() {
        val sender = FakeSender(setOf("shop"))
        app(bakerySchema, sender, resource = "bakeRoutes") { notifier ->
            val refused = client.post("/api/bakeRoutes") {
                caller("baker", "owner")
                json("""{"event":"order-ready","enabled":true,"destination":"typo"}""")
            }
            assertTrue(!refused.status.isSuccess(), refused.bodyAsText())
            assertTrue("destination" in refused.bodyAsText(), refused.bodyAsText())

            val badMention = client.post("/api/bakeRoutes") {
                caller("baker", "owner")
                json("""{"event":"order-ready","enabled":true,"destination":"shop","mention":"everyone"}""")
            }
            assertTrue(!badMention.status.isSuccess(), badMention.bodyAsText())

            val ok = client.post("/api/bakeRoutes") {
                caller("baker", "owner")
                json("""{"event":"order-ready","enabled":true,"destination":"shop"}""")
            }
            assertEquals(HttpStatusCode.Created, ok.status, ok.bodyAsText())
            notifier.publish("order-ready", "Order 12 is ready")
            assertEquals(listOf("shop" to NotifyMessage("Order 12 is ready", null)), sender.sent)
        }
    }

    @Test
    fun `only the editors can read or change routes, and an event key is unique`() {
        val sender = FakeSender(setOf("main"))
        app(schoolSchema, sender) {
            val body = """{"event":"enrolled","enabled":true,"destination":"main"}"""
            assertEquals(HttpStatusCode.Forbidden, client.post("/api/notifyRoutes") { caller("student", "pupil"); json(body) }.status)
            assertEquals(HttpStatusCode.Created, client.post("/api/notifyRoutes") { caller("clerk", "office"); json(body) }.status)
            assertTrue(!client.post("/api/notifyRoutes") { caller("clerk", "office"); json(body) }.status.isSuccess())
        }
    }

    @Test
    fun `every configuration problem is listed at once`() {
        val failure = assertFailsWith<Exception> {
            testApplication {
                application {
                    install(BaseKt) {
                        dataSource = InMemoryDataSource()
                        schema = schema { resource("things") { string("name") } }
                    }
                    install(BaseKtNotify) { }
                }
                startApplication()
            }
        }
        val message = generateSequence<Throwable>(failure) { it.cause }.mapNotNull { it.message }.joinToString("\n")
        assertTrue("the schema has no 'notifyRoutes' resource" in message, message)
        assertTrue("no sender" in message, message)
    }
}
