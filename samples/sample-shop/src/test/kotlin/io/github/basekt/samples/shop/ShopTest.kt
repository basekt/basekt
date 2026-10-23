package io.github.basekt.samples.shop

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ShopTest {
    /** Runs the sample as configured in `application.conf`, which lists [shopModule]. */
    private fun shop(test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        test()
    }

    private fun HttpRequestBuilder.json(text: String) {
        contentType(ContentType.Application.Json)
        setBody(text)
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun `anyone can browse the seeded catalogue`() = shop {
        assertEquals("ok", client.get("/health").bodyAsText())
        val lamps = client.get("/api/products?categoryId=lamps&sort=-price").json()["items"]!!.jsonArray
        assertEquals(listOf("Floor lamp", "Desk lamp"), lamps.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        val inStock = client.get("/api/categories/chairs/products?stock[gt]=0").json()["items"]!!.jsonArray
        assertEquals(0, inStock.size)
    }

    @Test
    fun `staff manage products and customers can't`() = shop {
        val body = """{"name": "Arm chair", "sku": "CHR-0002", "price": "149.00", "stock": 2, "categoryId": "chairs"}"""
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/products") { json(body) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/products") { bearerAuth("customer-token"); json(body) }.status)
        val created = client.post("/api/products") { bearerAuth("staff-token"); json(body) }
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("149", created.json()["price"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a customer places an order and staff ship it`() = shop {
        val order = client.post("/api/orders") {
            bearerAuth("customer-token")
            json("""{"customerId": "cus-1", "productId": "desk-lamp", "quantity": 2, "status": "placed", "placedAt": "2026-10-23T10:00:00Z"}""")
        }
        assertEquals(HttpStatusCode.Created, order.status)
        val id = order.json()["id"]!!.jsonPrimitive.content

        assertEquals(HttpStatusCode.Forbidden, client.patch("/api/orders/$id") { bearerAuth("customer-token"); json("""{"status": "shipped"}""") }.status)
        val shipped = client.patch("/api/orders/$id") { bearerAuth("staff-token"); json("""{"status": "shipped"}""") }
        assertEquals("shipped", shipped.json()["status"]!!.jsonPrimitive.content)

        val product = client.get("/api/orders/$id/product") { bearerAuth("staff-token") }.json()
        assertEquals("Desk lamp", product["name"]!!.jsonPrimitive.content)
        assertEquals(1, client.get("/api/customers/cus-1/orders") { bearerAuth("staff-token") }.json()["items"]!!.jsonArray.size)
    }

    @Test
    fun `bad orders are refused field by field`() = shop {
        val response = client.post("/api/orders") {
            bearerAuth("customer-token")
            json("""{"customerId": "cus-1", "productId": "teapot", "quantity": 0, "status": "lost"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        val codes = response.json()["fields"]!!.jsonArray.associate {
            it.jsonObject["field"]!!.jsonPrimitive.content to it.jsonObject["code"]!!.jsonPrimitive.content
        }
        assertEquals(mapOf("quantity" to "min", "status" to "enum"), codes)

        val missing = client.post("/api/orders") {
            bearerAuth("customer-token")
            json("""{"customerId": "cus-1", "productId": "teapot", "quantity": 1, "status": "placed"}""")
        }
        assertEquals("missing_reference", missing.json()["fields"]!!.jsonArray.single().jsonObject["code"]!!.jsonPrimitive.content)
    }
}
