package io.github.basekt.server

import io.github.basekt.data.Comparison
import io.github.basekt.data.Direction
import io.github.basekt.data.Filter
import io.github.basekt.data.Page
import io.github.basekt.data.Query
import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import io.github.basekt.schema.schema
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A made-up bike share: how many bikes stand at each dock is live, kept by the dock controller in memory. Riders see
 * the docks of their own zone and release a bike from one, which is stored; operators see every zone.
 */
private val bikeShareSchema: Schema = schema {
    resource("releases") {
        string("dockId") { required() }
        string("rider") { required() }
        permissions { read("operator") }
    }
    resource("docks") {
        provided()
        string("station") { required() }
        string("zone") { required() }
        int("bikes")
        string("lockCode") { hidden() }
        action("release") {
            input { string("rider") { required() } }
            allow(authenticated)
        }
        action("empty") { allow("operator") }
        permissions { read(authenticated); scope("zone", attribute = "zone", unless = setOf("operator")) }
    }
}

/** The dock controller's state: a plain list it filters, sorts and pages itself. */
private class DockController : RecordProvider {
    val docks = mutableListOf(
        dock("d1", "Harbour Gate", "north", 4),
        dock("d2", "Old Mill", "north", 0),
        dock("d3", "Tram Yard", "south", 7),
        dock("d4", "Harbour Steps", "south", 2),
    )
    var failing = false
    var calls = 0

    override val filters = mapOf(
        "zone" to setOf(FilterOperator.EQ, FilterOperator.IN),
        "bikes" to setOf(FilterOperator.GTE, FilterOperator.LTE),
        "station" to setOf(FilterOperator.CONTAINS),
    )
    override val sorts = setOf("bikes")

    override suspend fun list(query: Query): Page {
        calls++
        if (failing) error("The dock controller is offline at 10.0.0.7.")
        val matching = docks.filter { dock -> query.filter?.let { matches(it, dock) } ?: true }
        val sort = query.sort.singleOrNull()
        val sorted = matching.sortedWith(
            compareBy<Record> { dock -> sort?.let { (dock[it.field] as Value.Integer).value }?.let { if (sort.direction == Direction.DESC) -it else it } }
                .thenBy { (it[Resource.ID] as Value.Text).value },
        )
        val start = query.cursor?.toInt() ?: 0
        val page = sorted.drop(start).take(query.limit)
        return Page(page, (start + page.size).takeIf { it < sorted.size }?.toString())
    }

    override suspend fun get(id: String): Record? {
        calls++
        if (failing) error("The dock controller is offline at 10.0.0.7.")
        return docks.firstOrNull { it[Resource.ID] == Value.Text(id) }
    }

    private fun matches(filter: Filter, dock: Record): Boolean = when (filter) {
        is Filter.And -> filter.filters.all { matches(it, dock) }
        is Filter.Or -> filter.filters.any { matches(it, dock) }
        is Filter.In -> dock[filter.field] in filter.values
        is Filter.Contains -> (dock[filter.field] as Value.Text).value.contains(filter.text, ignoreCase = true)
        is Filter.Compare -> {
            val value = dock[filter.field]
            when (filter.op) {
                Comparison.EQ -> value == filter.value
                Comparison.GTE -> (value as Value.Integer).value >= (filter.value as Value.Integer).value
                Comparison.LTE -> (value as Value.Integer).value <= (filter.value as Value.Integer).value
                else -> error("Not declared: ${filter.op}")
            }
        }
        else -> error("Not declared: $filter")
    }

    companion object {
        fun dock(id: String, station: String, zone: String, bikes: Int): Record = mapOf(
            Resource.ID to Value.Text(id),
            "station" to Value.Text(station),
            "zone" to Value.Text(zone),
            "bikes" to Value.Integer(bikes.toLong()),
            "lockCode" to Value.Text("0000"),
        )
    }
}

/**
 * A made-up print shop: the print queue belongs to the printers' own service, reached over HTTP. Customers see their
 * own jobs and cancel them; staff see the whole queue.
 */
private val printShopSchema: Schema = schema {
    resource("printers") {
        string("name") { required() }
        permissions { read(everyone); write("staff") }
    }
    resource("jobs") {
        provided()
        belongsTo("printer", "printers")
        string("owner") { required() }
        string("document") { required() }
        enum("state", listOf("queued", "printing", "cancelled"))
        int("pages")
        action("cancel") { allow(authenticated) }
        permissions { read(authenticated); ownedBy("owner", unless = setOf("staff")) }
    }
}

/** The printers' service, as a stub with its own small API: `GET /jobs`, `GET /jobs/{id}`, `POST /jobs/{id}/cancel`. */
private class PrintService {
    val jobs = mutableListOf(
        job("j1", "p1", "ana", "poster.pdf", "printing", 2),
        job("j2", "p1", "ben", "thesis.pdf", "queued", 120),
        job("j3", "p2", "ana", "menu.pdf", "queued", 1),
    )
    var down = false
    var garbled = false
    var calls = 0

    fun ApplicationTestBuilder.serve() = externalServices {
        hosts(SERVICE) {
            routing {
                get("/jobs") {
                    calls++
                    if (down) return@get call.respondText("busy", status = HttpStatusCode.InternalServerError)
                    val owners = call.request.queryParameters["owner"]?.split(',')
                    val state = call.request.queryParameters["state"]
                    val found = jobs.filter { (owners == null || it.text("owner") in owners) && (state == null || it.text("state") == state) }
                    val start = call.request.queryParameters["offset"]?.toInt() ?: 0
                    val limit = call.request.queryParameters["limit"]!!.toInt()
                    val page = found.drop(start).take(limit)
                    val next = (start + page.size).takeIf { it < found.size }
                    call.respondText(JsonObject(mapOf("jobs" to JsonArray(page.map(::wire)), "next" to JsonPrimitive(next))).toString())
                }
                get("/jobs/{id}") {
                    calls++
                    val job = jobs.firstOrNull { it.text("id") == call.parameters["id"] }
                        ?: return@get call.respondText("{}", status = HttpStatusCode.NotFound)
                    call.respondText(wire(job).toString())
                }
                post("/jobs/{id}/cancel") {
                    val i = jobs.indexOfFirst { it.text("id") == call.parameters["id"] }
                    jobs[i] = jobs[i] + ("state" to "cancelled")
                    call.respondText("{}")
                }
            }
        }
    }

    private fun wire(job: Map<String, Any>): JsonObject = JsonObject(
        job.mapValues { (key, value) ->
            if (garbled && key == "pages") JsonPrimitive("many") else if (value is Int) JsonPrimitive(value) else JsonPrimitive(value as String)
        },
    )

    companion object {
        const val SERVICE = "http://print-service"

        fun job(id: String, printer: String, owner: String, document: String, state: String, pages: Int): Map<String, Any> =
            mapOf("id" to id, "printerId" to printer, "owner" to owner, "document" to document, "state" to state, "pages" to pages)

        private fun Map<String, Any>.text(key: String) = this[key] as String
    }
}

/** The provider of `jobs`: it turns a query into the print service's parameters, and its JSON into records. */
private class PrintQueue(private val http: HttpClient) : RecordProvider {
    override val filters = mapOf("owner" to setOf(FilterOperator.IN), "state" to setOf(FilterOperator.EQ))

    override suspend fun list(query: Query): Page {
        val response = http.get("${PrintService.SERVICE}/jobs") {
            for (condition in conditions(query.filter)) {
                when (condition) {
                    is Filter.In -> parameter(condition.field, condition.values.joinToString(",") { (it as Value.Text).value })
                    is Filter.Compare -> parameter(condition.field, (condition.value as Value.Text).value)
                    else -> error("Not declared: $condition")
                }
            }
            parameter("limit", query.limit)
            query.cursor?.let { parameter("offset", it) }
        }
        check(response.status.isSuccess()) { "The print service answered ${response.status}." }
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        return Page(body["jobs"]!!.jsonArray.map { record(it.jsonObject) }, body["next"]!!.jsonPrimitive.intOrNull?.toString())
    }

    override suspend fun get(id: String): Record? {
        val response = http.get("${PrintService.SERVICE}/jobs/$id")
        if (response.status == HttpStatusCode.NotFound) return null
        check(response.status.isSuccess()) { "The print service answered ${response.status}." }
        return record(Json.parseToJsonElement(response.bodyAsText()).jsonObject)
    }

    suspend fun cancel(id: String) {
        check(http.post("${PrintService.SERVICE}/jobs/$id/cancel").status.isSuccess())
    }

    private fun conditions(filter: Filter?): List<Filter> = when (filter) {
        null -> emptyList()
        is Filter.And -> filter.filters.flatMap(::conditions)
        else -> listOf(filter)
    }

    private fun record(json: JsonObject): Record = json.mapValues { (key, value) ->
        val primitive = value.jsonPrimitive
        if (key == "pages") primitive.intOrNull?.let { Value.Integer(it.toLong()) } ?: Value.Text(primitive.content)
        else Value.Text(primitive.content)
    }
}

class ProvidedResourcesTest {
    private val north = mapOf("zone" to listOf("north"))

    private fun bikeShare(test: suspend ApplicationTestBuilder.(DockController) -> Unit) = testApplication {
        val controller = DockController()
        val docks = bikeShareSchema.resource("docks")!!
        val releases = bikeShareSchema.resource("releases")!!
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = bikeShareSchema
                identities = headerIdentities
                providers { provide("docks", controller) }
                actions {
                    on("docks", "release") { input ->
                        create(releases, mapOf("dockId" to record!![Resource.ID], "rider" to input["rider"]))
                        null
                    }
                    on("docks", "empty") {
                        update(docks, (record!![Resource.ID] as Value.Text).value, mapOf("bikes" to Value.Integer(0)))
                        null
                    }
                }
            }
        }
        test(controller)
    }

    private suspend fun ApplicationTestBuilder.ids(path: String, attributes: Map<String, List<String>> = north, roles: List<String> = emptyList()): List<String> {
        val response = client.get(path) { caller("rider", roles, attributes) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.json()["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
    }

    @Test
    fun `a provided resource lists and gets its provider's records, without hidden fields`() = bikeShare {
        assertEquals(listOf("d1", "d2", "d3", "d4"), ids("/api/docks", emptyMap(), listOf("operator")))
        val dock = client.get("/api/docks/d1") { caller("rider", attributes = north) }.json()
        assertEquals("Harbour Gate", dock["station"]!!.jsonPrimitive.content)
        assertEquals(4, dock["bikes"]!!.jsonPrimitive.int)
        assertNull(dock["lockCode"])
        assertEquals(HttpStatusCode.NotFound, client.get("/api/docks/d9") { caller("rider", attributes = north) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/docks").status)
    }

    @Test
    fun `the filters and sorts the provider declares work, and pages follow its cursor`() = bikeShare {
        val operator = listOf("operator")
        assertEquals(listOf("d1", "d3"), ids("/api/docks?bikes[gte]=3", emptyMap(), operator))
        assertEquals(listOf("d1", "d4"), ids("/api/docks?station[contains]=harbour", emptyMap(), operator))
        assertEquals(listOf("d3", "d1", "d4", "d2"), ids("/api/docks?sort=-bikes", emptyMap(), operator))
        assertEquals(listOf("d3", "d4"), ids("/api/docks?zone=south", emptyMap(), operator))

        val first = client.get("/api/docks?limit=3") { caller("op", operator) }.json()
        assertEquals(3, first["items"]!!.jsonArray.size)
        val cursor = first["nextCursor"]!!.jsonPrimitive.content
        assertEquals(listOf("d4"), ids("/api/docks?limit=3&cursor=$cursor", emptyMap(), operator))
    }

    @Test
    fun `a filter or sort the provider doesn't declare is refused before it is called`() = bikeShare { controller ->
        val operator = listOf("operator")
        val gt = client.get("/api/docks?bikes[gt]=3") { caller("op", operator) }
        assertEquals(HttpStatusCode.BadRequest, gt.status)
        assertEquals("invalid_query", gt.json()["error"]!!.jsonPrimitive.content)
        assertEquals("'docks' can't filter 'bikes' with gt. It takes gte, lte.", gt.json()["message"]!!.jsonPrimitive.content)
        val byStation = client.get("/api/docks?sort=station") { caller("op", operator) }
        assertEquals("'docks' can't be sorted by 'station'. It sorts by bikes.", byStation.json()["message"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/docks?id=d1") { caller("op", operator) }.status)
        assertEquals(0, controller.calls)
    }

    @Test
    fun `every write is refused as read-only, whoever asks`() = bikeShare { controller ->
        val writes = listOf(
            client.post("/api/docks") { caller("op", listOf("operator")); json("""{"station": "New", "zone": "north"}""") },
            client.patch("/api/docks/d1") { caller("op", listOf("operator")); json("""{"bikes": 9}""") },
            client.delete("/api/docks/d1") { caller("op", listOf("operator")) },
            client.delete("/api/docks/d1"),
        )
        for (response in writes) {
            assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
            assertEquals("read_only", response.json()["error"]!!.jsonPrimitive.content)
        }
        assertEquals(4, controller.docks.size)
        val aggregate = client.get("/api/aggregate/docks?count") { caller("op", listOf("operator")) }
        assertEquals(HttpStatusCode.NotImplemented, aggregate.status)
    }

    @Test
    fun `the read scope reaches the provider as a filter, and the record outside it is not found`() = bikeShare { controller ->
        assertEquals(listOf("d1", "d2"), ids("/api/docks"))
        assertEquals(listOf("d1"), ids("/api/docks?bikes[gte]=1"))
        assertEquals(HttpStatusCode.NotFound, client.get("/api/docks/d3") { caller("rider", attributes = north) }.status)

        // A rider with no zone reaches no dock, and the provider isn't asked.
        val before = controller.calls
        assertEquals(emptyList(), ids("/api/docks", emptyMap()))
        assertEquals(before, controller.calls)
    }

    @Test
    fun `a provider that lets a record out of the scope doesn't reach the caller with it`() = testApplication {
        val leaky = object : RecordProvider by DockController() {
            override val filters = mapOf("zone" to setOf(FilterOperator.IN))
            override suspend fun list(query: Query): Page = DockController().list(Query(limit = query.limit))
        }
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = bikeShareSchema
                identities = headerIdentities
                providers { provide("docks", leaky) }
                actions {
                    on("docks", "release") { null }
                    on("docks", "empty") { null }
                }
            }
        }
        assertEquals(listOf("d1", "d2"), ids("/api/docks"))
    }

    @Test
    fun `an action runs on a provided record, writes stored records, and can't write the provided one`() = bikeShare {
        val released = client.post("/api/docks/d1/actions/release") { caller("rider", attributes = north); json("""{"rider": "Ana"}""") }
        assertEquals(HttpStatusCode.OK, released.status, released.bodyAsText())
        assertEquals("Harbour Gate", released.json()["station"]!!.jsonPrimitive.content)
        val stored = client.get("/api/releases") { caller("op", listOf("operator")) }.json()["items"]!!.jsonArray.single().jsonObject
        assertEquals("d1", stored["dockId"]!!.jsonPrimitive.content)

        val outside = client.post("/api/docks/d3/actions/release") { caller("rider", attributes = north); json("""{"rider": "Ana"}""") }
        assertEquals(HttpStatusCode.NotFound, outside.status)

        val emptied = client.post("/api/docks/d1/actions/empty") { caller("op", listOf("operator")) }
        assertEquals(HttpStatusCode.MethodNotAllowed, emptied.status)
        assertEquals("read_only", emptied.json()["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a failing provider answers unavailable with the request id, and keeps its cause to the log`() = bikeShare { controller ->
        controller.failing = true
        for (path in listOf("/api/docks", "/api/docks/d1")) {
            val response = client.get(path) { caller("op", listOf("operator")) }
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            val body = response.json()
            assertEquals("unavailable", body["error"]!!.jsonPrimitive.content)
            assertEquals(response.headers["X-Request-Id"], body["requestId"]!!.jsonPrimitive.content)
            assertFalse("10.0.0.7" in response.bodyAsText())
        }
        val action = client.post("/api/docks/d1/actions/release") { caller("op", listOf("operator")); json("""{"rider": "Ana"}""") }
        assertEquals(HttpStatusCode.ServiceUnavailable, action.status)
    }

    private fun printShop(test: suspend ApplicationTestBuilder.(PrintService) -> Unit) = testApplication {
        val service = PrintService()
        with(service) { serve() }
        val queue = PrintQueue(createClient { })
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = printShopSchema
                identities = headerIdentities
                providers { provide("jobs", queue) }
                actions {
                    on("jobs", "cancel") {
                        queue.cancel((record!![Resource.ID] as Value.Text).value)
                        null
                    }
                }
            }
        }
        client.post("/api/printers") { caller("s", listOf("staff")); json("""{"id": "p1", "name": "Front desk"}""") }
        test(service)
    }

    private suspend fun ApplicationTestBuilder.jobIds(path: String, user: String, vararg roles: String): List<String> {
        val response = client.get(path) { caller(user, roles.toList()) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.json()["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
    }

    @Test
    fun `a provider over another service serves its records, scoped to their owner`() = printShop {
        assertEquals(listOf("j1", "j2", "j3"), jobIds("/api/jobs", "clerk", "staff"))
        assertEquals(listOf("j1", "j3"), jobIds("/api/jobs", "ana"))
        assertEquals(listOf("j3"), jobIds("/api/jobs?state=queued", "ana"))
        assertEquals(listOf("j2"), jobIds("/api/jobs?limit=1&cursor=1", "clerk", "staff"))
        assertEquals(HttpStatusCode.NotFound, client.get("/api/jobs/j2") { caller("ana") }.status)

        // A provided record's belongsTo leads to the stored record.
        val printer = client.get("/api/jobs/j1/printer") { caller("ana") }
        assertEquals("Front desk", printer.json()["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a filter the service can't apply is refused, and writes never reach it`() = printShop { service ->
        val pages = client.get("/api/jobs?pages[gte]=10") { caller("clerk", listOf("staff")) }
        assertEquals(HttpStatusCode.BadRequest, pages.status)
        assertEquals("'jobs' can't be filtered on 'pages'.", pages.json()["message"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/jobs?state[ne]=queued") { caller("clerk", listOf("staff")) }.status)
        assertEquals(0, service.calls)

        val create = client.post("/api/jobs") { caller("ana"); json("""{"owner": "ana", "document": "x.pdf"}""") }
        assertEquals(HttpStatusCode.MethodNotAllowed, create.status)
        assertEquals(3, service.jobs.size)
    }

    @Test
    fun `an action on a provided record acts on the thing it describes, and answers with the record as it is now`() = printShop {
        val cancelled = client.post("/api/jobs/j3/actions/cancel") { caller("ana") }
        assertEquals(HttpStatusCode.OK, cancelled.status, cancelled.bodyAsText())
        assertEquals("cancelled", cancelled.json()["state"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/jobs/j2/actions/cancel") { caller("ana") }.status)
    }

    @Test
    fun `a service that fails or answers records that don't fit is unavailable`() = printShop { service ->
        service.down = true
        val down = client.get("/api/jobs") { caller("clerk", listOf("staff")) }
        assertEquals(HttpStatusCode.ServiceUnavailable, down.status)
        assertEquals(down.headers["X-Request-Id"], down.json()["requestId"]!!.jsonPrimitive.content)

        service.down = false
        service.garbled = true
        val garbled = client.get("/api/jobs") { caller("clerk", listOf("staff")) }
        assertEquals(HttpStatusCode.ServiceUnavailable, garbled.status)
        assertEquals("unavailable", garbled.json()["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an install lists every problem with the providers at once`() {
        val schema = schema {
            resource("notes") {
                string("text")
                permissions { read(everyone) }
            }
            resource("queue") {
                provided()
                string("label")
                string("team")
                string("token") { hidden() }
                permissions { read(everyone); scope("team", attribute = "team") }
            }
            resource("gauges") {
                provided()
                decimal("level")
                permissions { read(everyone) }
            }
        }
        val badQueue = object : RecordProvider {
            override val filters = mapOf("token" to setOf(FilterOperator.EQ), "label" to setOf(FilterOperator.CONTAINS), "team" to setOf(FilterOperator.EQ))
            override val sorts = setOf("colour")
            override suspend fun list(query: Query) = Page(emptyList(), null)
            override suspend fun get(id: String): Record? = null
        }
        val providers = ProvidersBuilder().apply {
            provide("queue", badQueue)
            provide("notes", badQueue)
            provide("ghosts", badQueue)
        }.build()
        val hooks = HooksBuilder().apply { beforeCreate("queue") { proceed(it) } }.build(emptyList())
        val failure = assertFailsWith<IllegalArgumentException> {
            ResourceService(schema, InMemoryDataSource(), hooks = hooks, providers = providers)
        }
        val message = failure.message!!
        for (expected in listOf(
            "A provider is registered for 'notes', which isn't provided(): its records live in the data source.",
            "A provider is registered for 'ghosts', which the schema doesn't have.",
            "'gauges' is provided(), but no provider is registered for it.",
            "The provider of 'queue' filters on 'token', which isn't a field the API serves.",
            "The provider of 'queue' sorts by 'colour', which isn't a field the API serves.",
            "'queue' is scoped by 'team', so its provider must support the 'in' filter on it.",
            "Hooks name 'queue', which is provided() and never written.",
        )) {
            assertTrue(expected in message, "Missing \"$expected\" in:\n$message")
        }
        assertFailsWith<IllegalArgumentException> {
            ProvidersBuilder().apply { provide("queue", badQueue); provide("queue", badQueue) }
        }
    }
}
