package io.github.basekt.server

import io.github.basekt.data.Comparison
import io.github.basekt.data.ConditionalUpdate
import io.github.basekt.data.ConditionalWrites
import io.github.basekt.data.Filter
import io.github.basekt.data.Query
import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.ActionTarget
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import io.github.basekt.schema.schema
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A made-up library: copies of a title are lent out one at a time, and each loan is a record of its own. */
private val loansSchema: Schema = schema {
    resource("copies") {
        string("title") { required() }
        int("available") { required(); min(0) }
        action("lend") {
            input { string("borrower") { required(); length(max = 40) } }
            allow("librarian")
            confirm("Lend a copy?")
        }
        permissions { read(authenticated); write("librarian") }
    }
    resource("loans") {
        reference("copyId", "copies") { required() }
        string("borrower") { required() }
        string("lentBy") { createdBy() }
        permissions { read("librarian") }
    }
}

/** A made-up team board: each team sees its own cards, and can archive its done cards in one go. */
private val boardSchema: Schema = schema {
    resource("cards") {
        string("team") { required() }
        string("title") { required() }
        enum("state", listOf("todo", "done", "archived")) { required() }
        action("archiveDone", target = ActionTarget.RESOURCE) { allow(authenticated) }
        action("reopen") { allow(authenticated) }
        permissions { read(authenticated); write(authenticated); scope("team", attribute = "team") }
    }
}

class ActionsTest {
    private val copies = loansSchema.resource("copies")!!
    private val loans = loansSchema.resource("loans")!!
    private val anyLeft = Filter.Compare("available", Comparison.GTE, Value.Integer(1))

    private fun library(test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = loansSchema
                identities = headerIdentities
                actions {
                    on("copies", "lend") { input ->
                        val copy = (record!![Resource.ID] as Value.Text).value
                        val taken = (data as ConditionalWrites).updateIf(copies, copy, anyLeft, increments = mapOf("available" to Value.Integer(-1)))
                        if (taken !is ConditionalUpdate.Updated) fail("No copies of this title are left.")
                        create(loans, mapOf("copyId" to Value.Text(copy), "borrower" to input["borrower"]))
                        null
                    }
                }
                hooks {
                    beforeCreate("loans") { loan ->
                        if ((loan["borrower"] as Value.Text).value == "banned") veto("borrower", "This borrower may not borrow.") else proceed(loan)
                    }
                }
            }
        }
        client.post("/api/copies") { caller("lib", listOf("librarian")); json("""{"id": "c1", "title": "Dune", "available": 1}""") }
        test()
    }

    private suspend fun ApplicationTestBuilder.lend(body: String, vararg roles: String = arrayOf("librarian")) =
        client.post("/api/copies/c1/actions/lend") { caller("lib", roles.toList()); json(body) }

    private suspend fun ApplicationTestBuilder.loanCount() =
        client.get("/api/loans") { caller("lib", listOf("librarian")) }.json()["items"]!!.jsonArray.size

    @Test
    fun `a record action runs its code and answers with the record`() = library {
        val response = lend("""{"borrower": "Ana"}""")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, response.json()["available"]!!.jsonPrimitive.long)
        val loan = client.get("/api/loans") { caller("lib", listOf("librarian")) }.json()["items"]!!.jsonArray.single().jsonObject
        assertEquals("Ana", loan["borrower"]!!.jsonPrimitive.content)
        assertEquals("lib", loan["lentBy"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an action checks its input and who runs it`() = library {
        assertEquals(HttpStatusCode.UnprocessableEntity, lend("{}").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, lend("""{"borrower": "Ana", "extra": 1}""").status)
        assertEquals(HttpStatusCode.Forbidden, lend("""{"borrower": "Ana"}""", "reader").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/copies/c1/actions/lend") { json("""{"borrower": "Ana"}""") }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/copies/c1/actions/burn") { caller("lib", listOf("librarian")) }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/copies/actions/lend") { caller("lib", listOf("librarian")) }.status)
        val missingCopy = client.post("/api/copies/c9/actions/lend") { caller("lib", listOf("librarian")); json("""{"borrower": "Ana"}""") }
        assertEquals(HttpStatusCode.NotFound, missingCopy.status)
    }

    @Test
    fun `a failed action rolls back everything it wrote`() = library {
        assertEquals(HttpStatusCode.OK, lend("""{"borrower": "Ana"}""").status)
        val soldOut = lend("""{"borrower": "Ben"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, soldOut.status)
        assertEquals("No copies of this title are left.", soldOut.json()["message"]!!.jsonPrimitive.content)
        assertEquals(1, loanCount())

        // A hook's veto on the action's own write undoes the copy it took.
        client.post("/api/copies") { caller("lib", listOf("librarian")); json("""{"id": "c2", "title": "Emma", "available": 1}""") }
        val vetoed = client.post("/api/copies/c2/actions/lend") { caller("lib", listOf("librarian")); json("""{"borrower": "banned"}""") }
        assertEquals(HttpStatusCode.UnprocessableEntity, vetoed.status)
        assertEquals(1, client.get("/api/copies/c2") { caller("lib") }.json()["available"]!!.jsonPrimitive.long)
        assertEquals(1, loanCount())
    }

    @Test
    fun `a resource action works on the caller's own records only`() = testApplication {
        val cards = boardSchema.resource("cards")!!
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = boardSchema
                identities = headerIdentities
                actions {
                    on("cards", "archiveDone") {
                        val team = caller!!.attributes.getValue("team").single()
                        val done = data.list(cards, Query(Filter.And(listOf(
                            Filter.Compare("team", Comparison.EQ, Value.Text(team)),
                            Filter.Compare("state", Comparison.EQ, Value.Text("done")),
                        )), limit = 100)).items
                        for (card in done) update(cards, (card[Resource.ID] as Value.Text).value, mapOf("state" to Value.Text("archived")))
                        JsonObject(mapOf("archived" to JsonPrimitive(done.size)))
                    }
                    on("cards", "reopen") {
                        update(cards, (record!![Resource.ID] as Value.Text).value, mapOf("state" to Value.Text("todo")))
                        null
                    }
                }
            }
        }
        val red = mapOf("team" to listOf("red"))
        val blue = mapOf("team" to listOf("blue"))
        for ((id, team, state) in listOf(Triple("k1", "red", "done"), Triple("k2", "red", "todo"), Triple("k3", "blue", "done"))) {
            client.post("/api/cards") { caller("u", attributes = mapOf("team" to listOf(team))); json("""{"id": "$id", "team": "$team", "title": "$id", "state": "$state"}""") }
        }

        val archived = client.post("/api/cards/actions/archiveDone") { caller("u", attributes = red) }
        assertEquals(HttpStatusCode.OK, archived.status)
        assertEquals(1, archived.json()["archived"]!!.jsonPrimitive.long)
        assertEquals("archived", client.get("/api/cards/k1") { caller("u", attributes = red) }.json()["state"]!!.jsonPrimitive.content)
        assertEquals("done", client.get("/api/cards/k3") { caller("u", attributes = blue) }.json()["state"]!!.jsonPrimitive.content)

        assertEquals(HttpStatusCode.NotFound, client.post("/api/cards/k3/actions/reopen") { caller("u", attributes = red) }.status)
        val reopened = client.post("/api/cards/k1/actions/reopen") { caller("u", attributes = red) }
        assertEquals("todo", reopened.json()["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun `every declared action needs code, and code needs a declared action`() {
        val missing = assertFailsWith<IllegalArgumentException> { ResourceService(loansSchema, InMemoryDataSource()) }
        assertTrue("copies.lend" in missing.message!!, missing.message)
        val extra = ActionsBuilder().apply {
            on("copies", "lend") { null }
            on("copies", "burn") { null }
        }.build()
        val unknown = assertFailsWith<IllegalArgumentException> { ResourceService(loansSchema, InMemoryDataSource(), actions = extra) }
        assertTrue("copies.burn" in unknown.message!!, unknown.message)
    }
}
