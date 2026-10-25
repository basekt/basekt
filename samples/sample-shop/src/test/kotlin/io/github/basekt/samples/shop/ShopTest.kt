package io.github.basekt.samples.shop

import io.github.basekt.auth.JwtSettings
import io.github.basekt.server.Identity
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.mergeWith
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class ShopTest {
    /** Runs the sample as configured in `application.conf`, which lists [shopModule], on a fresh H2 database. */
    private fun shop(test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment {
            config = ApplicationConfig("application.conf")
                .mergeWith(
                    MapApplicationConfig(
                        "shop.database.url" to "jdbc:h2:mem:shop-${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
                        "shop.files.dir" to Files.createTempDirectory("shop-files").toString(),
                    ),
                )
        }
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
    fun `the admin panel is served with the shop's theme`() = shop {
        val shell = client.get("/admin/r/products/desk-lamp")
        assertEquals(HttpStatusCode.OK, shell.status)
        assertContains(shell.bodyAsText(), "\"apiPath\":\"/admin/api\"")
        assertContains(shell.bodyAsText(), "<title>Sample shop admin</title>")
        assertContains(client.get("/admin/theme.css").bodyAsText(), "--primary: oklch(0.511 0.262 276.966);")
    }

    @Test
    fun `staff sign in to the panel, and refresh their token with a refresh token that works once`() = shop {
        val login = client.get("/admin").bodyAsText()
        assertContains(login, "\"refreshPath\":\"/auth/refresh\"")

        val refused = client.post("/auth/login") { json("""{"email":"sam@shop.test","password":"wrong"}""") }
        assertEquals(HttpStatusCode.Unauthorized, refused.status)
        assertEquals("unauthorized", refused.json()["error"]!!.jsonPrimitive.content)

        val signedIn = client.post("/auth/login") { json("""{"email":"sam@shop.test","password":"staff-password"}""") }.json()
        assertEquals(900, signedIn["expiresIn"]!!.jsonPrimitive.content.toInt())
        val token = signedIn["token"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.OK, client.get("/admin/api/schema") { bearerAuth(token) }.status)

        val first = signedIn["refreshToken"]!!.jsonPrimitive.content
        val refreshed = client.post("/auth/refresh") { json("""{"refreshToken":"$first"}""") }.json()
        val second = refreshed["refreshToken"]!!.jsonPrimitive.content
        assertTrue(second != first)
        assertEquals(HttpStatusCode.OK, client.get("/admin/api/schema") { bearerAuth(refreshed["token"]!!.jsonPrimitive.content) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/auth/refresh") { json("""{"refreshToken":"$first"}""") }.status)
    }

    @Test
    fun `products have images that staff upload and anyone sees`() = shop {
        val lamp = client.get("/api/products/desk-lamp").json()
        val image = lamp["image"]!!.jsonPrimitive.content
        val download = client.get("/files/$image")
        assertEquals(HttpStatusCode.OK, download.status)
        assertEquals(ContentType.Image.PNG, download.contentType())

        val png = client.get("/files/$image").bodyAsBytes()
        val upload = client.submitFormWithBinaryData("/files/images", formData {
            append("file", png, Headers.build {
                append(HttpHeaders.ContentType, "image/png")
                append(HttpHeaders.ContentDisposition, "filename=\"stool.png\"")
            })
        }) { bearerAuth("staff-token") }
        assertEquals(HttpStatusCode.Created, upload.status)
        val key = upload.json()["key"]!!.jsonPrimitive.content
        val updated = client.patch("/api/products/stool") { bearerAuth("staff-token"); json("""{"image": "$key"}""") }
        assertEquals(HttpStatusCode.OK, updated.status)
        assertEquals(key, updated.json()["image"]!!.jsonPrimitive.content)

        val anonymous = client.submitFormWithBinaryData("/files/images", formData { append("file", png, Headers.build { append(HttpHeaders.ContentType, "image/png") }) })
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
    }

    @Test
    fun `app code runs typed queries`() = shop {
        assertEquals(listOf("CHR-0001 0", "LMP-0002 3"), client.get("/low-stock").bodyAsText().lines())
    }

    @Test
    fun `staff manage products and customers can't`() = shop {
        val body = """{"name": "Arm chair", "sku": "CHR-0002", "price": "149.00", "cost": "80", "stock": 2, "categoryId": "chairs"}"""
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

    @Test
    fun `a price can't drop below the cost, on create or update`() = shop {
        val cheap = client.post("/api/products") {
            bearerAuth("staff-token")
            json("""{"name": "Cheap chair", "sku": "CHR-0003", "price": "5.00", "cost": "9.00", "stock": 1}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, cheap.status)
        val field = cheap.json()["fields"]!!.jsonArray.single().jsonObject
        assertEquals("price", field["field"]!!.jsonPrimitive.content)
        assertEquals("rejected", field["code"]!!.jsonPrimitive.content)

        // The desk lamp costs 11.00: dropping its price to 10 is refused, to 12 is fine.
        val drop = client.patch("/api/products/desk-lamp") { bearerAuth("staff-token"); json("""{"price": "10"}""") }
        assertEquals(HttpStatusCode.UnprocessableEntity, drop.status)
        val trim = client.patch("/api/products/desk-lamp") { bearerAuth("staff-token"); json("""{"price": "12"}""") }
        assertEquals("12", trim.json()["price"]!!.jsonPrimitive.content)

        // The admin API goes through the same hook.
        val viaAdmin = client.patch("/admin/api/resources/products/records/desk-lamp") { bearerAuth("staff-token"); json("""{"cost": "13"}""") }
        assertEquals(HttpStatusCode.UnprocessableEntity, viaAdmin.status)
    }

    @Test
    fun `JWTs signed with the configured secret are accepted`() = shop {
        val settings = JwtSettings.from(ApplicationConfig("application.conf").config("shop.jwt"))
        val token = settings.issue(Identity("sam", setOf("staff")), validFor = 1.hours)
        assertEquals(HttpStatusCode.OK, client.get("/api/customers") { bearerAuth(token) }.status)
        val forged = JwtSettings("sample-shop", "sample-shop", "someone-elses-secret-that-is-long-enough").issue(Identity("sam", setOf("staff")), 1.hours)
        val refused = client.get("/api/customers") { bearerAuth(forged) }
        assertEquals(HttpStatusCode.Unauthorized, refused.status)
        assertEquals("unauthorized", refused.json()["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the admin API shows staff the whole shop and customers only their part`() = shop {
        val staff = client.get("/admin/api/schema") { bearerAuth("staff-token") }.json()
        assertEquals(1, staff["version"]!!.jsonPrimitive.content.toInt())
        assertEquals(listOf("categories", "products", "customers", "orders"), staff["resources"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        val customer = client.get("/admin/api/schema") { bearerAuth("customer-token") }.json()
        val orders = customer["resources"]!!.jsonArray.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == "orders" }
        assertEquals(listOf("read", "create"), orders["operations"]!!.jsonArray.map { it.jsonPrimitive.content })

        val lamps = client.get("/admin/api/resources/products/records?search=lamp&sort=price") { bearerAuth("staff-token") }.json()
        assertEquals(2, lamps["total"]!!.jsonPrimitive.content.toInt())
        val csv = client.get("/admin/api/resources/products/export.csv?sort=id") { bearerAuth("staff-token") }.bodyAsText()
        val rows = csv.split("\r\n")
        assertEquals("id,name,sku,price,cost,stock,published,categoryId,image", rows[0])
        assertEquals("desk-lamp,Desk lamp,LMP-0001,24.9,11,12,true,lamps,images/seed/desk-lamp.png", rows[1])
    }
}
