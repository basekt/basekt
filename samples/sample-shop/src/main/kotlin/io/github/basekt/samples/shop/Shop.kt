package io.github.basekt.samples.shop

import io.github.basekt.admin.BaseKtAdmin
import io.github.basekt.auth.AuthIdentities
import io.github.basekt.auth.JwtSettings
import io.github.basekt.auth.baseKtJwt
import io.github.basekt.auth.bearerIdentities
import io.github.basekt.data.DataSource
import io.github.basekt.data.exposed.ExposedDataSource
import io.github.basekt.data.exposed.IndexSpec
import io.github.basekt.data.exposed.TableMapping
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import io.github.basekt.schema.schema
import io.github.basekt.server.BaseKt
import io.github.basekt.server.HookContext
import io.github.basekt.server.HooksBuilder
import io.github.basekt.server.Identity
import io.github.basekt.server.Verdict
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.cio.EngineMain
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

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
        decimal("cost") { required(); min("0") }
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
 * The shop's module:
 * - BaseKt on the Exposed adapter, over the database at `shop.database.url` (in-memory H2 by default), seeded with
 *   a small catalogue when it is empty;
 * - callers authenticated by JWTs signed with `shop.jwt`, or by the demo bearer tokens under `shop.tokens`;
 * - a hook that keeps every product's price at or above its cost;
 * - the JSON admin API under `basekt.admin.path`.
 */
public suspend fun Application.shopModule() {
    val config = environment.config
    val database = Database.connect(config.property("shop.database.url").getString())
    monitor.subscribe(ApplicationStopped) { TransactionManager.closeAndUnregister(database) }
    val dataSource = ExposedDataSource(
        database,
        shopSchema,
        TableMapping(indexes = listOf(IndexSpec("products", "price"), IndexSpec("orders", "status"))),
    )
    dataSource.createTables()
    if (dataSource.count(shopSchema.resource("categories")!!) == 0L) seed(dataSource)

    val demoTokens = config.configList("shop.tokens").associate { entry ->
        entry.property("token").getString() to
            Identity(entry.property("user").getString(), entry.property("roles").getList().toSet())
    }
    install(Authentication) {
        baseKtJwt("jwt", JwtSettings.from(config.config("shop.jwt")))
        bearerIdentities("demo-tokens") { token -> demoTokens[token] }
    }
    install(BaseKt) {
        this.dataSource = dataSource
        schema = shopSchema
        identities = AuthIdentities("jwt", "demo-tokens")
        hooks { priceStaysAboveCost() }
    }
    install(BaseKtAdmin)
    routing { get("/health") { call.respondText("ok") } }
}

/** Refuses a product whose price is below its cost, whether it is created that way or updated to it. */
private fun HooksBuilder.priceStaysAboveCost() {
    beforeCreate("products") { product -> belowCost(product) ?: proceed(product) }
    beforeUpdate("products") { update -> belowCost(update.merged) ?: proceed(update.changes) }
}

private fun HookContext.belowCost(product: Record): Verdict<Nothing>? {
    val price = product["price"] as Value.Decimal? ?: return null
    val cost = product["cost"] as Value.Decimal? ?: return null
    return if (price < cost) veto("price", "The price can't drop below the cost of $cost.") else null
}

private suspend fun seed(dataSource: DataSource) {
    fun resource(name: String) = shopSchema.resource(name)!!
    fun text(value: String) = Value.Text(value)
    dataSource.transaction { tx ->
        tx.create(resource("categories"), mapOf(Resource.ID to text("lamps"), "name" to text("Lamps"), "slug" to text("lamps")))
        tx.create(resource("categories"), mapOf(Resource.ID to text("chairs"), "name" to text("Chairs"), "slug" to text("chairs")))
        suspend fun product(id: String, name: String, sku: String, price: String, cost: String, stock: Long, category: String) =
            tx.create(
                resource("products"),
                mapOf(
                    Resource.ID to text(id),
                    "name" to text(name),
                    "sku" to text(sku),
                    "price" to Value.Decimal.of(price),
                    "cost" to Value.Decimal.of(cost),
                    "stock" to Value.Integer(stock),
                    "published" to Value.Bool(true),
                    "categoryId" to text(category),
                ),
            )
        product("desk-lamp", "Desk lamp", "LMP-0001", "24.90", "11.00", 12, "lamps")
        product("floor-lamp", "Floor lamp", "LMP-0002", "89.00", "42.50", 3, "lamps")
        product("stool", "Stool", "CHR-0001", "35.50", "20.00", 0, "chairs")
        tx.create(resource("customers"), mapOf(Resource.ID to text("cus-1"), "email" to text("ada@example.com"), "name" to text("Ada")))
    }
}
