package io.github.basekt.samples.shop

import io.github.basekt.data.DataSource
import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import io.github.basekt.schema.schema
import io.github.basekt.server.BaseKt
import io.github.basekt.server.Identity
import io.github.basekt.server.IdentityResolver
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.EngineMain
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/** Starts the sample on CIO, with the settings in `application.conf`. */
public fun main(args: Array<String>): Unit = EngineMain.main(args)

/** The shop's data model: a public catalogue, and orders that signed-in customers place and staff fulfil. */
public val shopSchema: Schema = schema {
    resource("categories") {
        string("name") { required(); length(min = 1, max = 60) }
        string("slug") { required(); pattern("[a-z0-9]+(-[a-z0-9]+)*", "Use lowercase words joined by dashes.") }
        hasMany("products", "products", foreignField = "categoryId")
        permissions { read(everyone); write("staff") }
    }
    resource("products") {
        string("name") { required(); length(min = 1, max = 120) }
        string("sku") { required(); pattern("[A-Z]{3}-[0-9]{4}") }
        decimal("price") { required(); min("0.01") }
        int("stock") { required(); min(0) }
        bool("published")
        belongsTo("category", "categories")
        permissions { read(everyone); write("staff") }
    }
    resource("customers") {
        string("email") { required(); pattern("[^@\\s]+@[^@\\s]+", "Must be an email address.") }
        string("name")
        hasMany("orders", "orders", foreignField = "customerId")
        permissions { read("staff"); write("staff") }
    }
    resource("orders") {
        belongsTo("customer", "customers") { required() }
        belongsTo("product", "products") { required() }
        int("quantity") { required(); min(1); max(99) }
        enum("status", listOf("placed", "paid", "shipped", "cancelled")) { required() }
        instant("placedAt")
        permissions { read("staff", "customer"); create("customer", "staff"); update("staff"); delete("staff") }
    }
}

/**
 * The shop's module: BaseKt on an in-memory data source, seeded with a small catalogue. Callers authenticate with the
 * demo bearer tokens listed under `shop.tokens`.
 */
public suspend fun Application.shopModule() {
    val dataSource = InMemoryDataSource()
    seed(dataSource)
    install(BaseKt) {
        this.dataSource = dataSource
        schema = shopSchema
        identities = demoTokens()
    }
    routing { get("/health") { call.respondText("ok") } }
}

/** Reads `shop.tokens` and resolves `Authorization: Bearer <token>` to its user and roles. Demo only. */
private fun Application.demoTokens(): IdentityResolver {
    val tokens = environment.config.configList("shop.tokens").associate { entry ->
        entry.property("token").getString() to
            Identity(entry.property("user").getString(), entry.property("roles").getList().toSet())
    }
    return IdentityResolver { call ->
        call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.let(tokens::get)
    }
}

private suspend fun seed(dataSource: DataSource) {
    fun resource(name: String) = shopSchema.resource(name)!!
    fun text(value: String) = Value.Text(value)
    dataSource.transaction { tx ->
        tx.create(resource("categories"), mapOf(Resource.ID to text("lamps"), "name" to text("Lamps"), "slug" to text("lamps")))
        tx.create(resource("categories"), mapOf(Resource.ID to text("chairs"), "name" to text("Chairs"), "slug" to text("chairs")))
        suspend fun product(id: String, name: String, sku: String, price: String, stock: Long, category: String) =
            tx.create(
                resource("products"),
                mapOf(
                    Resource.ID to text(id),
                    "name" to text(name),
                    "sku" to text(sku),
                    "price" to Value.Decimal.of(price),
                    "stock" to Value.Integer(stock),
                    "published" to Value.Bool(true),
                    "categoryId" to text(category),
                ),
            )
        product("desk-lamp", "Desk lamp", "LMP-0001", "24.90", 12, "lamps")
        product("floor-lamp", "Floor lamp", "LMP-0002", "89.00", 3, "lamps")
        product("stool", "Stool", "CHR-0001", "35.50", 0, "chairs")
        tx.create(resource("customers"), mapOf(Resource.ID to text("cus-1"), "email" to text("ada@example.com"), "name" to text("Ada")))
    }
}
