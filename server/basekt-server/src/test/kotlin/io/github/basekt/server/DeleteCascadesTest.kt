package io.github.basekt.server

import io.github.basekt.data.Comparison
import io.github.basekt.data.Filter
import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.OnDelete
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import io.github.basekt.schema.schema
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What deleting a record does to the records that belong to it, in a made-up warehouse and a made-up forum. */
class DeleteCascadesTest {
    /** Bins hold items; a move belongs to both its item and its bin; labels outlive the item they named. */
    private val warehouseSchema: Schema = schema {
        resource("bins") {
            string("code") { required() }
            permissions { read(authenticated); write("keeper") }
        }
        resource("items") {
            string("sku") { required() }
            belongsTo("bin", "bins")
            permissions { read(authenticated); write("keeper") }
        }
        resource("moves") {
            belongsTo("item", "items")
            belongsTo("bin", "bins", onDelete = OnDelete.CASCADE)
            permissions { read(authenticated); write("keeper") }
        }
        resource("labels") {
            string("text")
            belongsTo("item", "items", onDelete = OnDelete.SET_NULL)
            long("version") { version() }
            permissions { read(authenticated); write("keeper") }
        }
    }

    /**
     * Members write threads and posts, which go with their board; a member's reactions go with the member or the
     * post. Erasing a member removes everything they wrote, in one action.
     */
    private val forumSchema: Schema = schema {
        resource("members") {
            naturalId()
            string("name")
            action("erase") { allow("moderator") }
            permissions { read(authenticated); write("moderator") }
        }
        resource("boards") {
            string("title") { required() }
            permissions { read(authenticated); write("moderator") }
        }
        resource("threads") {
            string("title") { required() }
            reference("authorId", "members") { createdBy() }
            belongsTo("board", "boards", onDelete = OnDelete.CASCADE) { required() }
            permissions { read(authenticated); write(authenticated) }
        }
        resource("posts") {
            string("body") { required() }
            reference("authorId", "members") { createdBy() }
            belongsTo("thread", "threads", onDelete = OnDelete.CASCADE) { required() }
            permissions { read(authenticated); write(authenticated) }
        }
        resource("reactions") {
            belongsTo("member", "members", onDelete = OnDelete.CASCADE) { required() }
            belongsTo("post", "posts", onDelete = OnDelete.CASCADE) { required() }
            enum("kind", listOf("up", "down"))
            keyedBy("memberId", "postId")
            permissions { read(authenticated); write(authenticated) }
        }
    }

    private val written = CopyOnWriteArrayList<String>()
    private val committed = CopyOnWriteArrayList<String>()
    private val afterDeletes = CopyOnWriteArrayList<String>()

    private fun Change.label(): String = when (this) {
        is Change.Created -> "created ${resource.name}/${record[Resource.ID]}"
        is Change.Updated -> "updated ${resource.name}/${after[Resource.ID]}"
        is Change.Deleted -> "deleted ${resource.name}/${before[Resource.ID]}"
    }

    /** The code of the forum's `erase` action: every post and thread the member wrote, then the member. */
    private val erase: BaseKtConfig.() -> Unit = {
        actions {
            on("members", "erase") {
                val id = record!![Resource.ID] as Value.Text
                val mine = Filter.Compare("authorId", Comparison.EQ, id)
                val posts = deleteWhere(forumSchema.resource("posts")!!, mine)
                val threads = deleteWhere(forumSchema.resource("threads")!!, mine)
                delete(resource, id.value)
                if (posts + threads == 0) fail("There was nothing to erase.")
                buildJsonObject { put("posts", posts); put("threads", threads) }
            }
        }
    }

    private fun app(schema: Schema, setup: BaseKtConfig.() -> Unit = {}, test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                this.schema = schema
                identities = headerIdentities
                if (schema === forumSchema) erase()
                setup()
            }
            baseKt.service.extend(object : WriteExtension {
                override suspend fun written(change: Change, context: WriteContext) {
                    written += change.label()
                }

                override suspend fun committed(change: Change) {
                    committed += change.label()
                }
            })
        }
        test()
    }

    private val keeper: HttpRequestBuilder.() -> Unit = { caller("k1", listOf("keeper")) }
    private val moderator: HttpRequestBuilder.() -> Unit = { caller("mod", listOf("moderator")) }
    private fun member(id: String): HttpRequestBuilder.() -> Unit = { caller(id) }

    private suspend fun ApplicationTestBuilder.make(path: String, body: String, who: HttpRequestBuilder.() -> Unit) {
        val response = client.post(path) { who(); json(body) }
        assertEquals(HttpStatusCode.Created, response.status, "POST $path $body")
    }

    private suspend fun ApplicationTestBuilder.ids(path: String, who: HttpRequestBuilder.() -> Unit): List<String> =
        client.get("$path?sort=id&limit=100") { who() }.json()["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    @Test
    fun `a delete is refused while a record still belongs to it, by default`() = app(warehouseSchema) {
        make("/api/bins", """{"id": "b1", "code": "A-01"}""", keeper)
        make("/api/items", """{"id": "i1", "sku": "SKU-1", "binId": "b1"}""", keeper)
        make("/api/items", """{"id": "i2", "sku": "SKU-2", "binId": "b1"}""", keeper)

        val refused = client.delete("/api/bins/b1") { keeper() }
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals(
            "'bins/b1' can't be deleted while 2 'items' records point to it through 'bin', such as 'i1'.",
            refused.json()["message"]!!.jsonPrimitive.content,
        )
        assertEquals(listOf("i1", "i2"), ids("/api/items", keeper))

        client.delete("/api/items/i1") { keeper() }
        client.delete("/api/items/i2") { keeper() }
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/bins/b1") { keeper() }.status)
    }

    @Test
    fun `set null clears the reference, as an update with its hooks, and cascade deletes`() = app(
        warehouseSchema,
        setup = { hooks { afterUpdate("labels") { updated -> afterDeletes += "label ${updated.after[Resource.ID]} updated" } } },
    ) {
        make("/api/bins", """{"id": "b1", "code": "A-01"}""", keeper)
        make("/api/items", """{"id": "i1", "sku": "SKU-1", "binId": "b1"}""", keeper)
        make("/api/labels", """{"id": "l1", "text": "fragile", "itemId": "i1"}""", keeper)
        make("/api/moves", """{"id": "m1", "itemId": "i1", "binId": "b1"}""", keeper)

        // The move restricts deleting its item...
        assertEquals(HttpStatusCode.Conflict, client.delete("/api/items/i1") { keeper() }.status)
        written.clear()
        committed.clear()
        // ...but not the bin's delete, which cascades to the move. The item still restricts the bin.
        assertEquals(HttpStatusCode.Conflict, client.delete("/api/bins/b1") { keeper() }.status)
        client.delete("/api/moves/m1") { keeper() }
        written.clear()
        committed.clear()

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/items/i1") { keeper() }.status)
        val label = client.get("/api/labels/l1") { keeper() }.json()
        assertEquals(JsonNull, label["itemId"])
        assertEquals(2L, label["version"]!!.jsonPrimitive.content.toLong())
        assertEquals(listOf("label l1 updated"), afterDeletes)
        assertEquals(listOf("updated labels/l1", "deleted items/i1"), written)
        assertEquals(written, committed)
    }

    @Test
    fun `a record that the same delete removes doesn't hold it back`() = app(warehouseSchema) {
        make("/api/bins", """{"id": "b1", "code": "A-01"}""", keeper)
        make("/api/bins", """{"id": "b2", "code": "A-02"}""", keeper)
        make("/api/items", """{"id": "i1", "sku": "SKU-1", "binId": "b2"}""", keeper)
        make("/api/moves", """{"id": "m1", "itemId": "i1", "binId": "b1"}""", keeper)
        make("/api/moves", """{"id": "m2", "itemId": "i1", "binId": "b1"}""", keeper)

        written.clear()
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/bins/b1") { keeper() }.status)
        assertEquals(listOf("deleted bins/b1", "deleted moves/m1", "deleted moves/m2"), written)
        assertEquals(emptyList(), ids("/api/moves", keeper))
        // Now nothing holds the item back.
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/items/i1") { keeper() }.status)
    }

    private suspend fun ApplicationTestBuilder.seedForum() {
        make("/api/members", """{"id": "ann", "name": "Ann"}""", moderator)
        make("/api/members", """{"id": "ben", "name": "Ben"}""", moderator)
        make("/api/boards", """{"id": "general", "title": "General"}""", moderator)
        make("/api/boards", """{"id": "help", "title": "Help"}""", moderator)
        make("/api/threads", """{"id": "t1", "title": "Hello", "boardId": "general"}""", member("ann"))
        make("/api/threads", """{"id": "t2", "title": "Stuck", "boardId": "help"}""", member("ben"))
        make("/api/posts", """{"id": "p1", "body": "Hi all", "threadId": "t1"}""", member("ann"))
        make("/api/posts", """{"id": "p2", "body": "Hi Ann", "threadId": "t1"}""", member("ben"))
        make("/api/posts", """{"id": "p3", "body": "Try this", "threadId": "t2"}""", member("ann"))
        make("/api/reactions", """{"memberId": "ben", "postId": "p1", "kind": "up"}""", member("ben"))
        make("/api/reactions", """{"memberId": "ann", "postId": "p2", "kind": "up"}""", member("ann"))
        make("/api/reactions", """{"memberId": "ann", "postId": "p3", "kind": "down"}""", member("ann"))
    }

    @Test
    fun `a cascade walks down every level, running hooks and telling extensions of each record`() = app(
        forumSchema,
        setup = {
            hooks {
                afterDelete("posts") { deleted -> afterDeletes += "post ${deleted[Resource.ID]}" }
                afterDelete("reactions") { deleted -> afterDeletes += "reaction ${deleted[Resource.ID]}" }
            }
        },
    ) {
        seedForum()
        written.clear()
        committed.clear()
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/boards/general") { moderator() }.status)

        assertEquals(listOf("t2"), ids("/api/threads", moderator))
        assertEquals(listOf("p3"), ids("/api/posts", moderator))
        assertEquals(listOf("ann~p3"), ids("/api/reactions", moderator))
        assertEquals(
            listOf("deleted boards/general", "deleted threads/t1", "deleted posts/p1", "deleted posts/p2", "deleted reactions/ben~p1", "deleted reactions/ann~p2"),
            written,
        )
        assertEquals(written, committed)
        assertEquals(listOf("post p1", "post p2", "reaction ben~p1", "reaction ann~p2"), afterDeletes)
    }

    @Test
    fun `a veto anywhere in the cascade rolls the whole delete back`() = app(
        forumSchema,
        setup = { hooks { beforeDelete("reactions") { deletion -> if (deletion.id == "ann~p2") veto("Reactions to pinned posts stay.") else proceed(Unit) } } },
    ) {
        seedForum()
        committed.clear()
        val refused = client.delete("/api/boards/general") { moderator() }
        assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
        assertEquals(listOf("general", "help"), ids("/api/boards", moderator))
        assertEquals(listOf("p1", "p2", "p3"), ids("/api/posts", moderator))
        assertEquals(3, ids("/api/reactions", moderator).size)
        assertEquals(emptyList(), committed)
    }

    @Test
    fun `an erase action removes everything a member wrote, across resources, in one transaction`() = app(forumSchema) {
        seedForum()
        committed.clear()
        val erased = client.post("/api/members/ann/actions/erase") { moderator(); json("{}") }
        assertEquals("""{"posts":2,"threads":1}""", erased.bodyAsText())
        assertEquals(listOf("ben"), ids("/api/members", moderator))
        assertEquals(listOf("t2"), ids("/api/threads", moderator))
        // p2 was Ben's, but in Ann's thread, so it went with the thread.
        assertEquals(emptyList(), ids("/api/posts", moderator))
        assertEquals(emptyList(), ids("/api/reactions", moderator))
        assertTrue("deleted members/ann" in committed)
        assertEquals(committed.toSet().size, committed.size, "Each record is reported once")
        assertEquals(
            setOf("posts/p1", "posts/p3", "posts/p2", "threads/t1", "members/ann", "reactions/ben~p1", "reactions/ann~p2", "reactions/ann~p3"),
            committed.map { it.removePrefix("deleted ") }.toSet(),
        )
    }
}
