package io.github.basekt.server

import io.github.basekt.data.DataSource
import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import io.github.basekt.schema.schema
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A made-up expense tracker: claims are drafted, submitted, and approved or rejected by a manager. */
private val claimsSchema: Schema = schema {
    resource("claims") {
        decimal("amount") { required() }
        string("owner") { createdBy() }
        enum("status", listOf("draft", "pending", "approved", "rejected"))
        string("note")
        workflow("status", initial = "draft") {
            transition("submit", from = "draft", to = "pending") { allow(authenticated) }
            transition("approve", from = "pending", to = "approved") {
                allow("manager")
                input { string("note") { length(max = 200) } }
                confirm("Approve this claim?")
            }
            transition("reject", from = "pending", to = "rejected") { allow("manager") }
        }
        permissions { read(authenticated); write(authenticated) }
    }
}

/** A made-up help desk: tickets are worked on, closed with a resolution, and reopened. */
private val deskSchema: Schema = schema {
    resource("tickets") {
        string("subject") { required() }
        enum("state", listOf("open", "working", "closed"))
        long("version") { version() }
        workflow("state", initial = "open") {
            transition("start", from = "open", to = "working") { allow("agent") }
            transition("close", from = listOf("open", "working"), to = "closed") {
                allow("agent")
                input { string("summary") { required() } }
            }
            transition("reopen", from = "closed", to = "open") { allow("agent") }
        }
        permissions { read("agent"); write("agent") }
    }
    resource("resolutions") {
        reference("ticketId", "tickets") { required() }
        string("summary") { required() }
        permissions { read("agent") }
    }
}

class WorkflowsTest {
    private val manager: HttpRequestBuilder.() -> Unit = { caller("mia", listOf("manager")) }
    private val clerk: HttpRequestBuilder.() -> Unit = { caller("cal") }

    private fun claims(test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = claimsSchema
                identities = headerIdentities
                actions {
                    on("claims", "approve") { input ->
                        if ((record!!["owner"] as Value.Text?)?.value == caller?.id) fail("You can't approve your own claim.")
                        input["note"]?.let { update(resource, (record[Resource.ID] as Value.Text).value, mapOf("note" to it)) }
                        null
                    }
                }
            }
        }
        test()
    }

    private suspend fun ApplicationTestBuilder.run(id: String, transition: String, who: HttpRequestBuilder.() -> Unit, body: String = "{}") =
        client.post("/api/claims/$id/actions/$transition") { who(); json(body) }

    private suspend fun ApplicationTestBuilder.status(id: String) =
        client.get("/api/claims/$id") { clerk() }.json()["status"]!!.jsonPrimitive.content

    @Test
    fun `a claim starts as a draft, and only transitions move it`() = claims {
        val created = client.post("/api/claims") { clerk(); json("""{"id": "c1", "amount": "42.50"}""") }.json()
        assertEquals("draft", created["status"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.UnprocessableEntity, client.patch("/api/claims/c1") { clerk(); json("""{"status": "approved"}""") }.status)
        assertEquals(HttpStatusCode.UnprocessableEntity, client.post("/api/claims") { clerk(); json("""{"amount": "1", "status": "approved"}""") }.status)

        assertEquals("pending", run("c1", "submit", clerk).json()["status"]!!.jsonPrimitive.content)
        val approved = run("c1", "approve", manager, """{"note": "Fine."}""")
        assertEquals(HttpStatusCode.OK, approved.status)
        assertEquals("approved", approved.json()["status"]!!.jsonPrimitive.content)
        assertEquals("Fine.", approved.json()["note"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a transition checks the state, the role and its code`() = claims {
        client.post("/api/claims") { clerk(); json("""{"id": "c1", "amount": "10"}""") }
        val early = run("c1", "approve", manager)
        assertEquals(HttpStatusCode.Conflict, early.status)
        assertTrue("needs pending" in early.json()["message"]!!.jsonPrimitive.content)

        run("c1", "submit", clerk)
        assertEquals(HttpStatusCode.Forbidden, run("c1", "approve", clerk).status)
        assertEquals(HttpStatusCode.NotFound, run("c1", "escalate", manager).status)
        assertEquals(HttpStatusCode.UnprocessableEntity, run("c1", "approve", manager, """{"note": "${"x".repeat(201)}"}""").status)

        // A manager's own claim: the code refuses, and the state stays.
        client.post("/api/claims") { manager(); json("""{"id": "c2", "amount": "99"}""") }
        run("c2", "submit", manager)
        val own = run("c2", "approve", manager)
        assertEquals(HttpStatusCode.UnprocessableEntity, own.status)
        assertEquals("You can't approve your own claim.", own.json()["message"]!!.jsonPrimitive.content)
        assertEquals("pending", status("c2"))

        assertEquals(HttpStatusCode.OK, run("c1", "reject", manager).status)
        assertEquals(HttpStatusCode.Conflict, run("c1", "reject", manager).status)
        assertEquals("rejected", status("c1"))
    }

    @Test
    fun `transitions start from several states, act beside the change, and roll back together`() {
        val ran = mutableListOf<String>()
        val resolutions = deskSchema.resource("resolutions")!!
        testApplication {
            application {
                install(BaseKt) {
                    dataSource = InMemoryDataSource()
                    schema = deskSchema
                    identities = headerIdentities
                    actions {
                        on("tickets", "close") { input ->
                            val summary = (input["summary"] as Value.Text).value
                            create(resolutions, mapOf("ticketId" to record!![Resource.ID], "summary" to Value.Text(summary)))
                            if (summary == "nope") fail("summary", "Say what fixed it.")
                            null
                        }
                    }
                }
                baseKt.service.extend(object : WriteExtension {
                    override suspend fun ran(run: ActionRun, context: WriteContext) {
                        ran += "${run.action}:${context.caller?.id}"
                    }
                })
            }
            val agent: HttpRequestBuilder.() -> Unit = { caller("ava", listOf("agent")) }
            suspend fun move(id: String, transition: String, body: String = "{}") = client.post("/api/tickets/$id/actions/$transition") { agent(); json(body) }
            suspend fun ticket(id: String): JsonObject = client.get("/api/tickets/$id") { agent() }.json()

            client.post("/api/tickets") { agent(); json("""{"id": "t1", "subject": "Printer"}""") }
            client.post("/api/tickets") { agent(); json("""{"id": "t2", "subject": "Wi-Fi"}""") }
            assertEquals(1L, ticket("t1")["version"]!!.jsonPrimitive.content.toLong())

            // Closed straight from open, and from working.
            assertEquals(HttpStatusCode.OK, move("t1", "close", """{"summary": "Paper jam"}""").status)
            move("t2", "start")
            assertEquals(HttpStatusCode.OK, move("t2", "close", """{"summary": "Router reset"}""").status)
            assertEquals("closed", ticket("t2")["state"]!!.jsonPrimitive.content)
            assertEquals(3L, ticket("t2")["version"]!!.jsonPrimitive.content.toLong())

            move("t1", "reopen")
            val refused = move("t1", "close", """{"summary": "nope"}""")
            assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
            assertEquals("open", ticket("t1")["state"]!!.jsonPrimitive.content)
            assertEquals(2, client.get("/api/resolutions") { agent() }.json()["items"]!!.jsonArray.size)

            assertEquals(listOf("close:ava", "start:ava", "close:ava", "reopen:ava"), ran)
        }
    }

    @Test
    fun `install refuses code for unknown transitions and workflows without conditional writes`() {
        val extra = ActionsBuilder().apply { on("claims", "escalate") { null } }.build()
        val unknown = assertFailsWith<IllegalArgumentException> { ResourceService(claimsSchema, InMemoryDataSource(), actions = extra) }
        assertTrue("claims.escalate" in unknown.message!!, unknown.message)

        val plain = InMemoryDataSource().let { memory -> object : DataSource by memory {} }
        val noConditions = assertFailsWith<IllegalArgumentException> { ResourceService(claimsSchema, plain) }
        assertTrue("ConditionalWrites" in noConditions.message!!, noConditions.message)
    }
}
