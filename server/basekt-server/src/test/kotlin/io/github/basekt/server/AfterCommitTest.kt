package io.github.basekt.server

import io.github.basekt.data.DataSource
import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.OnDelete
import io.github.basekt.schema.Operation
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import io.github.basekt.schema.model
import io.github.basekt.schema.schema
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.Logger
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A made-up community garden: plots hold beds, which go with their plot, and a bed can be dug over in one action.
 * Watering logs outlive the bed they named.
 */
private val gardenSchema: Schema = schema {
    resource("plots") {
        string("name") { required() }
        permissions { read(authenticated); write("gardener") }
    }
    resource("beds") {
        string("crop") { required() }
        belongsTo("plot", "plots", onDelete = OnDelete.CASCADE)
        action("dig") { allow("gardener"); input { string("crop") { required() } } }
        permissions { read(authenticated); write("gardener") }
    }
    resource("waterings") {
        int("litres") { min(1) }
        belongsTo("bed", "beds", onDelete = OnDelete.SET_NULL)
        permissions { read(authenticated); write("gardener") }
    }
}

/** A made-up rowing club: typed boats, booked out and back in. */
@Serializable
data class Boat(val id: String? = null, val name: String, val seats: Int = 1, val out: Boolean = false)

private val boats = model<Boat>("boats") {
    field(Boat::name) { length(min = 2) }
    field(Boat::seats) { min(1); max(8) }
    action("lend") { allow("coach") }
    permissions { read(authenticated); write("coach") }
}

/** After-commit hooks: once per record that committed, on every transport, never for a write that rolled back. */
class AfterCommitTest {
    private val seen = CopyOnWriteArrayList<String>()

    private fun garden(
        dataSource: DataSource = InMemoryDataSource(),
        logger: Logger? = null,
        hooks: HooksBuilder.() -> Unit,
        test: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        if (logger != null) environment { log = logger }
        application {
            install(BaseKt) {
                this.dataSource = dataSource
                schema = gardenSchema
                identities = headerIdentities
                actions {
                    on("beds", "dig") { input ->
                        val waterings = gardenSchema.resource("waterings")!!
                        create(waterings, mapOf("litres" to Value.Integer(1), "bedId" to record!![Resource.ID]))
                        update(resource, (record!![Resource.ID] as Value.Text).value, mapOf("crop" to input["crop"]))
                        if ((input["crop"] as Value.Text).value == "weeds") fail("Nobody plants weeds.")
                        null
                    }
                }
                hooks(hooks)
            }
        }
        test()
    }

    private fun HooksBuilder.recordAll() {
        for (name in listOf("plots", "beds", "waterings")) {
            afterCommit(name) { change -> seen += "${change.operation.name.lowercase()} $name/${change.id}" }
        }
    }

    private suspend fun ApplicationTestBuilder.post(path: String, body: String) =
        client.post(path) { asUser("gardener"); json(body) }

    @Test
    fun `it runs once after the write committed, and sees the record as it is now and before`() = garden(hooks = {
        afterCreate("beds") { _ -> seen += "inside the transaction" }
        afterCommit("beds") { change ->
            seen += "${change.operation} ${change.id}: ${(change.before?.get("crop") as Value.Text?)?.value} -> ${(change.record?.get("crop") as Value.Text?)?.value} by ${caller?.id}"
        }
    }) {
        post("/api/plots", """{"id": "p1", "name": "North"}""")
        assertEquals(HttpStatusCode.Created, post("/api/beds", """{"id": "b1", "crop": "beans", "plotId": "p1"}""").status)
        assertEquals(listOf("inside the transaction", "CREATE b1: null -> beans by u1"), seen)
        client.patch("/api/beds/b1") { asUser("gardener"); json("""{"crop": "peas"}""") }
        client.delete("/api/beds/b1") { asUser("gardener") }
        assertEquals(listOf("UPDATE b1: beans -> peas by u1", "DELETE b1: peas -> null by u1"), seen.drop(2))
    }

    @Test
    fun `the record it sees is already readable outside the transaction`() {
        val dataSource = InMemoryDataSource()
        garden(dataSource, hooks = {
            afterCommit("plots") { change -> seen += "stored: ${dataSource.get(resource, change.id) != null}" }
        }) {
            post("/api/plots", """{"id": "p1", "name": "North"}""")
            assertEquals(listOf("stored: true"), seen)
        }
    }

    @Test
    fun `it doesn't run for a vetoed, refused or failed write`() = garden(hooks = {
        recordAll()
        beforeCreate("plots") { plot -> if (plot["name"] == Value.Text("Swamp")) veto("name", "Too wet.") else proceed(plot) }
        afterUpdate("plots") { _ -> error("The fence fell over.") }
    }) {
        assertEquals(HttpStatusCode.UnprocessableEntity, post("/api/plots", """{"name": "Swamp"}""").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, post("/api/beds", """{"plotId": "p9"}""").status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/plots") { asUser("visitor"); json("""{"name": "East"}""") }.status)
        post("/api/plots", """{"id": "p1", "name": "North"}""")
        seen.clear()
        // An after-hook that throws rolls the update back, so nothing committed.
        assertEquals(HttpStatusCode.InternalServerError, client.patch("/api/plots/p1") { asUser("gardener"); json("""{"name": "South"}""") }.status)
        assertEquals(emptyList(), seen)
    }

    @Test
    fun `it runs for an action's writes once it committed, and not at all for a failed action`() = garden(hooks = { recordAll() }) {
        post("/api/beds", """{"id": "b1", "crop": "beans"}""")
        seen.clear()
        val failed = post("/api/beds/b1/actions/dig", """{"crop": "weeds"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, failed.status)
        assertEquals(emptyList(), seen, "the action wrote a watering and an update before it failed")

        assertEquals(HttpStatusCode.OK, post("/api/beds/b1/actions/dig", """{"crop": "kale"}""").status)
        assertEquals(2, seen.size)
        assertTrue(seen[0].startsWith("create waterings/"), seen.toString())
        assertEquals("update beds/b1", seen[1])
    }

    @Test
    fun `it runs once for each record a delete cascades to or clears`() = garden(hooks = { recordAll() }) {
        post("/api/plots", """{"id": "p1", "name": "North"}""")
        post("/api/beds", """{"id": "b1", "crop": "beans", "plotId": "p1"}""")
        post("/api/beds", """{"id": "b2", "crop": "kale", "plotId": "p1"}""")
        post("/api/waterings", """{"id": "w1", "litres": 3, "bedId": "b2"}""")
        seen.clear()
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/plots/p1") { asUser("gardener") }.status)
        assertEquals(listOf("update waterings/w1", "delete plots/p1", "delete beds/b1", "delete beds/b2"), seen)
    }

    @Test
    fun `a failing hook is logged with the request id, the write stands and the next hook still runs`() {
        val logged = CopyOnWriteArrayList<Pair<String, Throwable?>>()
        garden(logger = recordingLogger(logged), hooks = {
            afterCommit("plots") { _ -> error("The message queue is down.") }
            afterCommit("plots") { change -> seen += "told ${change.id} to $requestId" }
        }) {
            val created = client.post("/api/plots") { asUser("gardener"); header("X-Request-Id", "dig-7"); json("""{"id": "p1", "name": "North"}""") }
            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals(HttpStatusCode.OK, client.get("/api/plots/p1") { asUser("gardener") }.status)
            assertEquals(listOf("told p1 to dig-7"), seen)
            val (message, cause) = logged.single { "after-commit" in it.first }
            assertTrue("dig-7" in message && "plots" in message && "p1" in message, message)
            assertEquals("The message queue is down.", cause?.message)
        }
    }

    @Test
    fun `it runs once when the data source runs the transaction again after a conflict`() =
        garden(RetryingOnce(InMemoryDataSource()), hooks = { recordAll() }) {
            post("/api/plots", """{"id": "p1", "name": "North"}""")
            post("/api/beds", """{"id": "b1", "crop": "beans", "plotId": "p1"}""")
            post("/api/beds/b1/actions/dig", """{"crop": "kale"}""")
            client.delete("/api/plots/p1") { asUser("gardener") }
            assertEquals(1, seen.count { it == "create plots/p1" })
            assertEquals(1, seen.count { it == "update beds/b1" })
            assertEquals(1, seen.count { it.startsWith("create waterings/") })
            assertEquals(1, seen.count { it == "delete beds/b1" })
        }

    @Test
    fun `hooks must name resources of the schema`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ResourceService(gardenSchema, InMemoryDataSource(), hooks = HooksBuilder().apply { afterCommit("greenhouses") { } }.build())
        }
        assertEquals("Hooks name resources the schema doesn't have: greenhouses.", error.message)
    }

    private fun club(hooks: HooksBuilder.() -> Unit, test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                identities = headerIdentities
                resources { resource(boats) }
                actions {
                    on("boats", "lend") { _ ->
                        update(resource, (record!![Resource.ID] as Value.Text).value, mapOf("out" to Value.Bool(true)))
                        null
                    }
                }
                hooks(hooks)
            }
        }
        test()
    }

    @Test
    fun `the typed form sees the model's class`() = club({
        afterCommit { change: Committed<Boat> ->
            seen += when (change) {
                is Committed.Created -> "new ${change.record.name} with ${change.record.seats} seats"
                is Committed.Updated -> "${change.record.name}: out ${change.before.out} -> ${change.record.out}"
                is Committed.Deleted -> "gone ${change.before.name}"
            }
        }
    }) {
        client.post("/api/boats") { asUser("coach"); json("""{"id": "k1", "name": "Heron", "seats": 4}""") }
        client.post("/api/boats/k1/actions/lend") { asUser("coach") }
        client.delete("/api/boats/k1") { asUser("coach") }
        assertEquals(listOf("new Heron with 4 seats", "Heron: out false -> true", "gone Heron"), seen)
    }

    @Test
    fun `the typed form with a model runs through the service with no transport, and no request id`() = runTest {
        val schema = schema { resource(boats) }
        val service = ResourceService(
            schema,
            InMemoryDataSource(),
            hooks = HooksBuilder().apply {
                afterCommit(boats) { change -> seen += "${change.operation} ${change.id} ${change.record?.name} $requestId" }
            }.build(),
            actions = ActionsBuilder().apply { on("boats", "lend") { _ -> null } }.build(),
        )
        val coach = Identity("cora", setOf("coach"))
        val resource = service.resource("boats")
        service.create(resource, coach, JsonObject(mapOf("id" to JsonPrimitive("k2"), "name" to JsonPrimitive("Swift"))))
        service.delete(resource, coach, "k2")
        assertEquals(listOf("${Operation.CREATE} k2 Swift null", "${Operation.DELETE} k2 null null"), seen)
    }

    @Test
    fun `a typed hook for a class the schema doesn't declare is refused at install, every one named`() {
        val error = assertFailsWith<IllegalStateException> {
            testApplication {
                application {
                    install(BaseKt) {
                        dataSource = InMemoryDataSource()
                        schema = gardenSchema
                        hooks {
                            afterCommit { _: Committed<Boat> -> }
                            afterCommit { _: Committed<Ticket> -> }
                        }
                    }
                }
                startApplication()
            }
        }
        val message = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }.joinToString("\n")
        assertTrue("Boat" in message && "Ticket" in message, message)
    }
}

/** A data source that runs every transaction twice, as an adapter does after a conflict: the first run rolls back. */
private class RetryingOnce(private val inner: InMemoryDataSource) : DataSource by inner {
    private object Conflict : RuntimeException() {
        private fun readResolve(): Any = Conflict
    }

    override suspend fun <T> transaction(block: suspend (DataSource) -> T): T {
        try {
            inner.transaction { tx -> block(tx); throw Conflict }
        } catch (_: Conflict) {
            // Run it again, as an adapter would.
        }
        return inner.transaction(block)
    }
}

/** A logger that keeps the messages and causes of its errors in [into], and drops everything else. */
private fun recordingLogger(into: MutableList<Pair<String, Throwable?>>): Logger =
    Proxy.newProxyInstance(Logger::class.java.classLoader, arrayOf(Logger::class.java)) { _, method, args ->
        when {
            method.name == "error" && args != null && args[0] is String -> {
                into += (args[0] as String) to args.lastOrNull() as? Throwable
                null
            }
            method.returnType == Boolean::class.javaPrimitiveType -> method.name.startsWith("isError")
            method.name == "getName" -> "test"
            else -> null
        }
    } as Logger
