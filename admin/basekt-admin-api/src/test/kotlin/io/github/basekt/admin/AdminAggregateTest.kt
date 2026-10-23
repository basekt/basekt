package io.github.basekt.admin

import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.schema
import io.github.basekt.server.BaseKt
import io.github.basekt.server.Identity
import io.github.basekt.server.IdentityResolver
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** A made-up bakery chain: each shop logs its bakes, and sees only its own. */
private val bakerySchema = schema {
    resource("bakes") {
        string("shop") { required() }
        string("bread") { required() }
        int("loaves") { required() }
        permissions { read("baker"); write("baker"); scope("shop", attribute = "shop") }
    }
}

/** `X-User`, and `X-Shop` for the caller's one shop. */
private val shopIdentities = IdentityResolver { call ->
    call.request.headers["X-User"]?.let { Identity(it, setOf("baker"), mapOf("shop" to setOfNotNull(call.request.headers["X-Shop"]))) }
}

class AdminAggregateTest {
    private fun HttpRequestBuilder.baker(shop: String) {
        header("X-User", "b")
        header("X-Shop", shop)
    }

    @Test
    fun `aggregates take search, keep to the caller's scope and match the wire types`() = testApplication {
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = bakerySchema
                identities = shopIdentities
            }
            install(BaseKtAdmin)
        }
        for ((shop, bread, loaves) in listOf(Triple("east", "rye", 10), Triple("east", "rye", 5), Triple("east", "sourdough", 8), Triple("west", "rye", 99))) {
            val created = client.post("/admin/api/resources/bakes/records") {
                baker(shop)
                contentType(ContentType.Application.Json)
                setBody("""{"bread": "$bread", "loaves": $loaves}""")
            }
            assertEquals(HttpStatusCode.Created, created.status)
        }

        val response = client.get("/admin/api/resources/bakes/aggregate?groupBy=bread&sum=loaves&count&search=rye") { baker("east") }
        assertEquals(HttpStatusCode.OK, response.status)
        val page = Json.decodeFromString(AdminAggregatePage.serializer(), response.json().toString())
        assertEquals(false, page.hasMore)
        val rye = page.groups.single()
        assertEquals("rye", rye.key["bread"]!!.jsonPrimitive.content)
        assertEquals("15", rye.values["sum_loaves"]!!.jsonPrimitive.content)
        assertEquals("2", rye.values["count"]!!.jsonPrimitive.content)
    }
}
