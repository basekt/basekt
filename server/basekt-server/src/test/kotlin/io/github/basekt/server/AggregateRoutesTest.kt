package io.github.basekt.server

import io.github.basekt.data.DataSource
import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.schema
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A made-up warehouse ledger: every movement of an item in or out of a site, which staff see for their own sites. */
private val ledgerSchema: Schema = schema {
    resource("items") {
        string("name") { required() }
        permissions { read(authenticated); write("admin") }
    }
    resource("movements") {
        reference("itemId", "items") { required() }
        string("siteId") { required() }
        int("quantity") { required() }
        decimal("cost")
        instant("at") { required() }
        permissions { read("staff", "admin"); write("staff", "admin"); scope("siteId", attribute = "site", unless = setOf("admin")) }
    }
}

/** A made-up fitness tracker: workouts of a kind, with minutes and the calories some of them logged. */
private val fitnessSchema: Schema = schema {
    resource("workouts") {
        enum("kind", listOf("run", "swim", "lift")) { required() }
        long("minutes") { required() }
        decimal("calories")
        string("member") { required() }
        permissions { read("member"); write("member") }
    }
}

class AggregateRoutesTest {
    private fun app(schema: Schema, source: DataSource = InMemoryDataSource(), test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(BaseKt) {
                dataSource = source
                this.schema = schema
                identities = headerIdentities
                maxAggregateGroups = 50
            }
        }
        test()
    }

    private val admin: HttpRequestBuilder.() -> Unit = { caller("boss", listOf("admin")) }
    private fun staff(site: String): HttpRequestBuilder.() -> Unit = { caller("s", listOf("staff"), mapOf("site" to listOf(site))) }

    private suspend fun ApplicationTestBuilder.seedLedger() {
        for (item in listOf("i1", "i2")) client.post("/api/items") { admin(); json("""{"id": "$item", "name": "$item"}""") }
        val moves = listOf(
            """{"itemId": "i1", "siteId": "north", "quantity": 10, "cost": "2.00", "at": "2026-10-01T08:00:00Z"}""",
            """{"itemId": "i1", "siteId": "north", "quantity": -3, "cost": "2.50", "at": "2026-10-05T08:00:00Z"}""",
            """{"itemId": "i1", "siteId": "south", "quantity": 4, "cost": "2.00", "at": "2026-10-02T08:00:00Z"}""",
            """{"itemId": "i2", "siteId": "north", "quantity": 7, "cost": "1.00", "at": "2026-10-03T08:00:00Z"}""",
        )
        for (move in moves) assertEquals(HttpStatusCode.Created, client.post("/api/movements") { admin(); json(move) }.status)
    }

    private suspend fun ApplicationTestBuilder.groups(path: String, who: HttpRequestBuilder.() -> Unit): List<JsonObject> {
        val response = client.get(path) { who() }
        assertEquals(HttpStatusCode.OK, response.status, path)
        return response.json()["groups"]!!.jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.key(field: String) = this["key"]!!.jsonObject[field]!!.jsonPrimitive.content
    private fun JsonObject.value(name: String) = this["values"]!!.jsonObject[name]!!

    @Test
    fun `sums of movements give stock on hand per item and site`() = app(ledgerSchema) {
        seedLedger()
        val stock = groups("/api/aggregate/movements?groupBy=itemId,siteId&sum=quantity&count&max=at", admin)
        assertEquals(listOf("i1/north", "i1/south", "i2/north"), stock.map { "${it.key("itemId")}/${it.key("siteId")}" })
        assertEquals(listOf("7", "4", "7"), stock.map { it.value("sum_quantity").jsonPrimitive.content })
        assertEquals("2", stock.first().value("count").jsonPrimitive.content)
        assertEquals("2026-10-05T08:00:00Z", stock.first().value("max_at").jsonPrimitive.content)

        val average = groups("/api/aggregate/movements?groupBy=itemId&avg=cost", admin)
        assertEquals("2.166666666667", average.first().value("avg_cost").jsonPrimitive.content)

        val busiest = client.get("/api/aggregate/movements?groupBy=siteId&sum=quantity&quantity[gt]=0&sort=-sum_quantity&limit=1") { admin() }.json()
        assertEquals("north", busiest["groups"]!!.jsonArray.single().jsonObject.key("siteId"))
        assertEquals("17", busiest["groups"]!!.jsonArray.single().jsonObject.value("sum_quantity").jsonPrimitive.content)
        assertEquals("true", busiest["hasMore"]!!.jsonPrimitive.content)
    }

    @Test
    fun `movements per week and per day make a time series`() = app(ledgerSchema) {
        seedLedger()
        // 2026-10-01 is a Thursday, so the 5th starts a new week.
        val weeks = groups("/api/aggregate/movements?groupBy=at:week&sum=quantity", admin)
        assertEquals(listOf("2026-09-28T00:00:00Z" to "21", "2026-10-05T00:00:00Z" to "-3"), weeks.map { it.key("at") to it.value("sum_quantity").jsonPrimitive.content })
        val latestDays = groups("/api/aggregate/movements?groupBy=at:day&count&sort=-at&limit=2", staff("north"))
        assertEquals(listOf("2026-10-05T00:00:00Z", "2026-10-03T00:00:00Z"), latestDays.map { it.key("at") })
        // 08:00 UTC is 17:00 in Tokyo, the same day; in Honolulu it is 22:00 the day before.
        val honolulu = groups("/api/aggregate/movements?groupBy=at:day&count&timeZone=Pacific/Honolulu&sort=at&limit=1", admin)
        assertEquals("2026-09-30T10:00:00Z", honolulu.single().key("at"))
        val perSiteMonth = groups("/api/aggregate/movements?groupBy=siteId,at:month&count", admin)
        assertEquals(listOf("north/2026-10-01T00:00:00Z", "south/2026-10-01T00:00:00Z"), perSiteMonth.map { "${it.key("siteId")}/${it.key("at")}" })
    }

    @Test
    fun `staff see the totals of their own sites only`() = app(ledgerSchema) {
        seedLedger()
        val south = groups("/api/aggregate/movements?groupBy=siteId&sum=quantity", staff("south"))
        assertEquals(listOf("south"), south.map { it.key("siteId") })
        val total = groups("/api/aggregate/movements?sum=quantity", staff("north")).single()
        assertEquals("14", total.value("sum_quantity").jsonPrimitive.content)
    }

    @Test
    fun `totals without groups, counts of a field, and min and max per kind`() = app(fitnessSchema) {
        val member: HttpRequestBuilder.() -> Unit = { caller("m", listOf("member")) }
        for (body in listOf(
            """{"kind": "run", "minutes": 30, "calories": "300", "member": "ana"}""",
            """{"kind": "run", "minutes": 45, "member": "ben"}""",
            """{"kind": "swim", "minutes": 20, "calories": "180.5", "member": "ana"}""",
        )) client.post("/api/workouts") { member(); json(body) }

        val all = groups("/api/aggregate/workouts?count=*,calories&sum=minutes,calories", member).single()
        assertEquals(JsonObject(emptyMap()), all["key"])
        assertEquals("3", all.value("count").jsonPrimitive.content)
        assertEquals("2", all.value("count_calories").jsonPrimitive.content)
        assertEquals("95", all.value("sum_minutes").jsonPrimitive.content)
        assertEquals("480.5", all.value("sum_calories").jsonPrimitive.content)

        val perKind = groups("/api/aggregate/workouts?groupBy=kind&min=minutes&max=minutes,calories", member)
        assertEquals(listOf("run", "swim"), perKind.map { it.key("kind") })
        assertEquals(listOf("30", "20"), perKind.map { it.value("min_minutes").jsonPrimitive.content })
        assertEquals(listOf("45", "20"), perKind.map { it.value("max_minutes").jsonPrimitive.content })

        val none = groups("/api/aggregate/workouts?groupBy=kind&sum=calories&kind=lift", member)
        assertEquals(emptyList(), none)
        val nothing = groups("/api/aggregate/workouts?sum=calories&kind=lift", member).single()
        assertEquals(JsonNull, nothing.value("sum_calories"))
    }

    @Test
    fun `bad queries, missing access and sources that can't aggregate get typed errors`() {
        app(fitnessSchema) {
            val member: HttpRequestBuilder.() -> Unit = { caller("m", listOf("member")) }
            for (query in listOf("sum=member", "count=colour", "count&sort=minutes", "", "count&limit=51", "count&minutes=many", "sum=", "groupBy=kind:month&count", "groupBy=minutes:fortnight&count",
                "count&timeZone=Mars/Olympus", "count&timeZone=%2B08:00")) {
                val response = client.get("/api/aggregate/workouts?$query") { member() }
                assertEquals(HttpStatusCode.BadRequest, response.status, query)
                assertEquals("invalid_query", response.json()["error"]!!.jsonPrimitive.content, query)
            }
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/aggregate/workouts?count") { caller("x", listOf("guest")) }.status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/aggregate/races?count") { member() }.status)
        }
        val plain = InMemoryDataSource().let { memory -> object : DataSource by memory {} }
        app(fitnessSchema, plain) {
            val response = client.get("/api/aggregate/workouts?count") { caller("m", listOf("member")) }
            assertEquals(HttpStatusCode.NotImplemented, response.status)
            assertTrue("aggregates" in response.json()["message"]!!.jsonPrimitive.content)
        }
    }
}
