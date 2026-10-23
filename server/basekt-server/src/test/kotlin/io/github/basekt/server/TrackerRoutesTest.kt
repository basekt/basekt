package io.github.basekt.server

import io.github.basekt.data.memory.InMemoryDataSource
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Configuration, lifecycle, filters, sorting and pagination, on a made-up task tracker. */
class TrackerRoutesTest {
    private fun tracker(config: MapApplicationConfig = MapApplicationConfig(), test: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            environment { this.config = config }
            application {
                install(BaseKt) {
                    dataSource = InMemoryDataSource()
                    schema = trackerSchema
                    identities = headerIdentities
                }
            }
            test()
        }

    private suspend fun ApplicationTestBuilder.seed(base: String = "/api") {
        client.postJson("$base/projects", """{"id": "p1", "title": "Launch"}""", "lead")
        val tasks = listOf(
            """{"id": "t1", "summary": "Write docs", "projectId": "p1", "priority": 2, "estimate": "1.5", "due": "2026-11-01T09:00:00Z", "done": true}""",
            """{"id": "t2", "summary": "Fix the build", "projectId": "p1", "priority": 1, "estimate": "0.25", "due": "2026-10-30T09:00:00Z", "done": false}""",
            """{"id": "t3", "summary": "Review docs", "projectId": "p1", "priority": 2, "estimate": "3", "done": false}""",
            """{"id": "t4", "summary": "Plan", "projectId": "p1", "priority": 5, "estimate": "10"}""",
            """{"id": "t5", "summary": "Celebrate", "projectId": "p1"}""",
        )
        for (task in tasks) assertEquals(HttpStatusCode.Created, client.postJson("$base/tasks", task).status)
    }

    private suspend fun ApplicationTestBuilder.ids(path: String): List<String> {
        val response = client.get(path) { asUser() }
        assertEquals(HttpStatusCode.OK, response.status, path)
        return response.json()["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
    }

    @Test
    fun `filters use field and operator parameters`() = tracker {
        seed()
        assertEquals(listOf("t1", "t3"), ids("/api/tasks?priority=2"))
        assertEquals(listOf("t3", "t4"), ids("/api/tasks?estimate[gte]=2.5"))
        assertEquals(listOf("t1", "t2"), ids("/api/tasks?due[lt]=2026-12-01T00:00:00Z"))
        assertEquals(listOf("t1", "t3"), ids("/api/tasks?summary[contains]=DOCS"))
        assertEquals(listOf("t2", "t4"), ids("/api/tasks?priority[in]=1,5"))
        assertEquals(listOf("t4", "t5"), ids("/api/tasks?done[null]=true"))
        assertEquals(listOf("t2", "t3"), ids("/api/tasks?done=false&priority[lte]=2"))
        assertEquals(listOf("t1"), ids("/api/tasks?id[ne]=t2&due[null]=false"))
    }

    @Test
    fun `sort and cursor parameters page through the records`() = tracker {
        seed()
        assertEquals(listOf("t5", "t4", "t3", "t1", "t2"), ids("/api/tasks?sort=-priority,-estimate&limit=10"))

        val walked = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = client.get("/api/tasks?sort=-priority,-estimate&limit=2" + (cursor?.let { "&cursor=$it" } ?: "")) { asUser() }.json()
            walked += page["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            cursor = page["nextCursor"]!!.takeUnless { it is JsonNull }?.jsonPrimitive?.content
        } while (cursor != null)
        assertEquals(listOf("t5", "t4", "t3", "t1", "t2"), walked)
    }

    @Test
    fun `bad query parameters are typed errors`() = tracker {
        seed()
        for (query in listOf("colour=red", "priority=high", "priority[near]=2", "sort=colour", "limit=0", "limit=101", "cursor=garbage", "done[null]=maybe")) {
            val response = client.get("/api/tasks?$query") { asUser() }
            assertEquals(HttpStatusCode.BadRequest, response.status, query)
            assertEquals("invalid_query", response.json()["error"]!!.jsonPrimitive.content, query)
        }
    }

    @Test
    fun `authenticated access refuses anonymous callers`() = tracker {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/tasks").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/tasks") { asUser() }.status)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/api/projects", """{"title": "x"}""", "member").status)
    }

    @Test
    fun `a required reference can't be cleared`() = tracker {
        seed()
        val response = client.patchJson("/api/tasks/t1", """{"projectId": null, "priority": 9}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        val fields = response.json()["fields"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content }
        assertEquals(listOf("required", "max"), fields)
    }

    @Test
    fun `the base path and page sizes come from ApplicationConfig`() = tracker(
        MapApplicationConfig("basekt.basePath" to "/v1/", "basekt.pageSize.default" to "2", "basekt.pageSize.max" to "3"),
    ) {
        seed("/v1")
        val page = client.get("/v1/tasks") { asUser() }.json()
        assertEquals(2, (page["items"] as JsonArray).size)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/tasks?limit=4") { asUser() }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/tasks") { asUser() }.status)
    }

    @Test
    fun `BaseKtStarted is raised with the runtime once the application starts`() = testApplication {
        var started: BaseKtRuntime? = null
        application {
            monitor.subscribe(BaseKtStarted) { started = it }
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                resources { resource("notes") { string("body"); permissions { read(everyone) } } }
            }
        }
        startApplication()
        val runtime = assertNotNull(started)
        assertEquals(listOf("notes"), runtime.schema.resources.map { it.name })
        assertEquals("/api", runtime.basePath)
    }

    @Test
    fun `install refuses a missing data source, schema or validator`() {
        val noSource = assertFailsWith<IllegalArgumentException> {
            testApplication { application { install(BaseKt) { schema = trackerSchema } }; startApplication() }
        }
        assertTrue("dataSource" in noSource.message!!)
        val noValidator = assertFailsWith<IllegalArgumentException> {
            testApplication {
                application { install(BaseKt) { dataSource = InMemoryDataSource(); schema = catalogueSchema } }
                startApplication()
            }
        }
        assertTrue("isbn" in noValidator.message!!)
    }
}
