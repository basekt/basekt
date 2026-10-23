package io.github.basekt.server

import io.github.basekt.data.Repository
import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Value
import io.github.basekt.schema.model
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

/** A made-up help desk: typed tickets in dynamic queues. */
@Serializable
data class Ticket(
    val id: String? = null,
    val subject: String,
    val priority: Int = 3,
    val status: Status = Status.OPEN,
    val resolvedAt: Instant? = null,
    val queueId: String? = null,
)

@Serializable
enum class Status {
    @SerialName("open") OPEN,
    @SerialName("closed") CLOSED,
}

/** The tickets model, declared once and shared by the schema and the repository in a hook. */
private val tickets = model<Ticket>("tickets") {
    field(Ticket::subject) { length(min = 3) }
    field(Ticket::priority) { min(1); max(5) }
    belongsTo(Ticket::queueId, "queues")
    permissions { read(authenticated); write("agent") }
}

class TypedHooksTest {
    private fun desk(hooks: HooksBuilder.() -> Unit, test: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                identities = headerIdentities
                resources {
                    resource("queues") {
                        string("name") { required() }
                        permissions { read(authenticated); write("agent") }
                    }
                    resource(tickets)
                }
                hooks(hooks)
            }
        }
        test()
    }

    @Test
    fun `typed before-hooks modify and veto through REST`() = desk({
        beforeCreate { new: Ticket -> proceed(new.copy(subject = new.subject.trim())) }
        beforeUpdate { old: Ticket, new: Ticket ->
            when {
                old.status == Status.CLOSED && new.status == Status.OPEN -> veto("A closed ticket stays closed.")
                new.status == Status.CLOSED && new.resolvedAt == null -> proceed(new.copy(resolvedAt = Instant.parse("2026-10-23T12:00:00Z")))
                else -> proceed(new)
            }
        }
    }) {
        val created = client.postJson("/api/tickets", """{"id": "t1", "subject": "  Printer jam  "}""", "agent").json()
        assertEquals("Printer jam", created["subject"]!!.jsonPrimitive.content)
        assertEquals("open", created["status"]!!.jsonPrimitive.content)

        val closed = client.patchJson("/api/tickets/t1", """{"status": "closed"}""", "agent").json()
        assertEquals("2026-10-23T12:00:00Z", closed["resolvedAt"]!!.jsonPrimitive.content)

        val reopened = client.patchJson("/api/tickets/t1", """{"status": "open"}""", "agent")
        assertEquals(HttpStatusCode.UnprocessableEntity, reopened.status)
        assertEquals("rejected", reopened.json()["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a typed field veto names the field, and typed hooks see validated values only`() = desk({
        beforeCreate { new: Ticket -> if (new.priority == 1 && new.queueId == null) veto("queueId", "Urgent tickets need a queue.") else proceed(new) }
    }) {
        val urgent = client.postJson("/api/tickets", """{"subject": "Server down", "priority": 1}""", "agent")
        assertEquals(HttpStatusCode.UnprocessableEntity, urgent.status)
        assertEquals("queueId", urgent.json()["fields"]!!.jsonArray.single().jsonObject["field"]!!.jsonPrimitive.content)
        // Validation runs before hooks, so the hook never sees a priority of 9.
        val invalid = client.postJson("/api/tickets", """{"subject": "Odd", "priority": 9}""", "agent")
        assertEquals("max", invalid.json()["fields"]!!.jsonArray.single().jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `typed after-hooks and delete hooks use the write's transaction through a repository`() = desk({
        afterCreate { created: Ticket ->
            if (created.priority == 1) {
                Repository(tickets, data).create(Ticket(subject = "Follow up ${created.id}", priority = 2))
            }
        }
        beforeDelete { old: Ticket -> if (old.status == Status.OPEN) veto("Close the ticket before deleting it.") else proceed(Unit) }
    }) {
        client.postJson("/api/tickets", """{"id": "t1", "subject": "Outage", "priority": 1}""", "agent")
        val subjects = client.get("/api/tickets?sort=subject") { asUser() }.json()["items"]!!.jsonArray.map { it.jsonObject["subject"]!!.jsonPrimitive.content }
        assertEquals(listOf("Follow up t1", "Outage"), subjects)
        assertEquals(HttpStatusCode.UnprocessableEntity, client.delete("/api/tickets/t1") { asUser("agent") }.status)
        client.patchJson("/api/tickets/t1", """{"status": "closed"}""", "agent")
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/tickets/t1") { asUser("agent") }.status)
    }

    @Test
    fun `the runtime hands out repositories by class`() = desk({}) {
        startApplication()
        val runtime = application.baseKt
        val repository = runtime.repository<Ticket>()
        val created = repository.create(Ticket(subject = "From a job"))
        assertEquals(listOf(created), repository.list { where { Ticket::status eq Status.OPEN } }.items)
        assertEquals(Value.Text("open"), runtime.dataSource.get(runtime.schema.resource("tickets")!!, created.id!!)!!["status"])
        val missing = assertFailsWith<IllegalStateException> { runtime.repository<Status>() }
        assertEquals(true, "No typed resource is declared" in missing.message!!, missing.message)
    }

    @Test
    fun `a typed hook for a class the schema doesn't declare is refused at install`() {
        assertFailsWith<IllegalStateException> {
            testApplication {
                application {
                    install(BaseKt) {
                        dataSource = InMemoryDataSource()
                        schema = trackerSchema
                        hooks { beforeCreate { new: Ticket -> proceed(new) } }
                    }
                }
                startApplication()
            }
        }
    }
}
