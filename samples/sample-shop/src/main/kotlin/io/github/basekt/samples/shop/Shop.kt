package io.github.basekt.samples.shop

import io.github.basekt.admin.BaseKtAdmin
import io.github.basekt.admin.ui.BaseKtAdminUi
import io.github.basekt.admin.ui.Oklch
import io.github.basekt.admin.ui.Radius
import io.github.basekt.admin.ui.Theme
import io.github.basekt.admin.ui.Token
import io.github.basekt.admin.ui.theme
import io.github.basekt.files.BaseKtFiles
import io.github.basekt.files.MEGABYTE
import io.github.basekt.storage.BlobKey
import io.github.basekt.storage.BlobStore
import io.github.basekt.storage.local.LocalBlobStore
import io.github.basekt.auth.AuthIdentities
import io.github.basekt.auth.JwtSettings
import io.github.basekt.auth.baseKtJwt
import io.github.basekt.auth.bearerIdentities
import io.github.basekt.data.DataSource
import io.github.basekt.data.Repository
import io.github.basekt.data.exposed.ExposedDataSource
import io.github.basekt.data.exposed.IndexSpec
import io.github.basekt.data.exposed.TableMapping
import io.github.basekt.schema.Model
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import io.github.basekt.schema.model
import io.github.basekt.schema.schema
import io.github.basekt.server.BaseKt
import io.github.basekt.server.HookContext
import io.github.basekt.server.HooksBuilder
import io.github.basekt.server.Identity
import io.github.basekt.server.Verdict
import io.github.basekt.server.beforeCreate
import io.github.basekt.server.baseKt
import io.github.basekt.server.beforeUpdate
import io.github.basekt.server.repository
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.cio.EngineMain
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.io.Buffer
import kotlinx.io.write
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.time.Instant

/** Starts the sample on CIO, with the settings in `application.conf`. */
public fun main(args: Array<String>): Unit = EngineMain.main(args)

/** A product of the catalogue. Typed: its fields come from this class. */
@Serializable
public data class Product(
    /** The id, generated when null. */
    val id: String? = null,
    /** The name shown to customers. */
    val name: String,
    /** The stock-keeping unit, such as `LMP-0001`. */
    val sku: String,
    /** The selling price. */
    val price: Value.Decimal,
    /** What the product costs the shop; the price never drops below it. */
    val cost: Value.Decimal,
    /** How many are in stock. */
    val stock: Int,
    /** Whether customers see it. */
    val published: Boolean? = null,
    /** The category's id. */
    val categoryId: String? = null,
    /** The product's picture: a file in the `images` bucket. */
    val image: String? = null,
)

/** A customer's order of one product. Typed: its fields come from this class. */
@Serializable
public data class Order(
    /** The id, generated when null. */
    val id: String? = null,
    /** The customer's id. */
    val customerId: String,
    /** The product's id. */
    val productId: String,
    /** How many. */
    val quantity: Int,
    /** Where the order is. */
    val status: OrderStatus,
    /** When it was placed. */
    val placedAt: Instant? = null,
)

/** Where an [Order] is. */
@Serializable
public enum class OrderStatus {
    /** Placed by the customer. */
    @SerialName("placed") PLACED,

    /** Paid. */
    @SerialName("paid") PAID,

    /** Sent to the customer. */
    @SerialName("shipped") SHIPPED,

    /** Cancelled. */
    @SerialName("cancelled") CANCELLED,
}

/** The typed `products` resource. */
public val Products: Model<Product> = model("products") {
    field(Product::name) { length(min = 1, max = 120) }
    field(Product::sku) { pattern("[A-Z]{3}-[0-9]{4}") }
    field(Product::price) { min("0.01") }
    field(Product::cost) { min("0") }
    field(Product::stock) { min(0) }
    belongsTo(Product::categoryId, "categories")
    file(Product::image, "images")
    permissions { read(everyone); write("staff") }
}

/** The typed `orders` resource. */
public val Orders: Model<Order> = model("orders") {
    belongsTo(Order::customerId, "customers")
    belongsTo(Order::productId, "products")
    field(Order::quantity) { min(1); max(99) }
    permissions { read("staff", "customer"); create("customer", "staff"); update("staff"); delete("staff") }
}

/**
 * The shop's data model: a public catalogue, and orders that signed-in customers place and staff fulfil. Products and
 * orders are typed; categories and customers stay dynamic, to show both side by side.
 */
public val shopSchema: Schema = schema {
    resource("categories") {
        string("name") { required(); length(min = 1, max = 60) }
        string("slug") { required(); pattern("[a-z0-9]+(-[a-z0-9]+)*", "Use lowercase words joined by dashes.") }
        hasMany("products", "products", foreignField = "categoryId")
        permissions { read(everyone); write("staff") }
    }
    resource(Products)
    resource("customers") {
        string("email") { required(); pattern("[^@\\s]+@[^@\\s]+", "Must be an email address.") }
        string("name")
        hasMany("orders", "orders", foreignField = "customerId")
        permissions { read("staff"); write("staff") }
    }
    resource(Orders)
}

/**
 * The shop's module:
 * - BaseKt on the Exposed adapter, over the database at `shop.database.url` (in-memory H2 by default), seeded with
 *   a small catalogue when it is empty;
 * - callers authenticated by JWTs signed with `shop.jwt`, or by the demo bearer tokens under `shop.tokens`;
 * - a hook that keeps every product's price at or above its cost;
 * - product images in the `images` bucket, stored under `shop.files.dir` and served under `basekt.files.path`;
 * - the JSON admin API under `basekt.admin.path`, and the admin panel under `basekt.adminUi.path`, themed with [shopTheme];
 * - `GET /low-stock`, a typed query in app code.
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
    val images = LocalBlobStore(Path.of(config.property("shop.files.dir").getString()))
    if (dataSource.count(shopSchema.resource("categories")!!) == 0L) seed(dataSource, images)

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
        models += listOf(Products, Orders)
        hooks { priceStaysAboveCost() }
    }
    install(BaseKtFiles) {
        store = images
        bucket("images") {
            maxBytes = 2 * MEGABYTE
            accept("image/png", "image/jpeg", "image/webp")
            read(everyone)
            write("staff")
        }
    }
    install(BaseKtAdmin)
    install(BaseKtAdminUi) {
        brand { name = "Sample shop" }
        theme { use(shopTheme) }
    }
    routing {
        get("/health") { call.respondText("ok") }
        // App code reads typed records with a repository and a typed query. The catalogue is public, so skipping
        // the permission checks a repository skips is fine here.
        get("/low-stock") {
            val low = baseKt.repository<Product>().list {
                where { (Product::stock lt 5) and (Product::published eq true) }
                orderBy(Product::stock)
            }
            call.respondText(low.items.joinToString("\n") { "${it.sku} ${it.stock}" })
        }
    }
}

/** The admin panel's look: shadcn's neutral theme with an indigo accent and slightly tighter corners. */
public val shopTheme: Theme = theme {
    radius = Radius.rem(0.5)
    light {
        for (token in listOf(Token.Primary, Token.Ring, Token.SidebarPrimary)) this[token] = Oklch(0.511, 0.262, 276.966)
        this[Token.PrimaryForeground] = Oklch(0.962, 0.018, 272.314)
        this[Token.SidebarPrimaryForeground] = Oklch(0.962, 0.018, 272.314)
    }
    dark {
        for (token in listOf(Token.Primary, Token.Ring, Token.SidebarPrimary)) this[token] = Oklch(0.673, 0.182, 276.935)
        this[Token.PrimaryForeground] = Oklch(0.257, 0.09, 281.288)
        this[Token.SidebarPrimaryForeground] = Oklch(0.257, 0.09, 281.288)
    }
}

/** Refuses a product whose price is below its cost, whether it is created that way or updated to it. */
private fun HooksBuilder.priceStaysAboveCost() {
    beforeCreate { new: Product -> belowCost(new) ?: proceed(new) }
    beforeUpdate { _: Product, new: Product -> belowCost(new) ?: proceed(new) }
}

private fun HookContext.belowCost(product: Product): Verdict<Nothing>? =
    if (product.price < product.cost) veto("price", "The price can't drop below the cost of ${product.cost}.") else null

private suspend fun seed(dataSource: DataSource, images: BlobStore) {
    fun resource(name: String) = shopSchema.resource(name)!!
    fun text(value: String) = Value.Text(value)
    dataSource.transaction { tx ->
        tx.create(resource("categories"), mapOf(Resource.ID to text("lamps"), "name" to text("Lamps"), "slug" to text("lamps")))
        tx.create(resource("categories"), mapOf(Resource.ID to text("chairs"), "name" to text("Chairs"), "slug" to text("chairs")))
        val products = Repository(Products, tx)
        fun price(literal: String) = Value.Decimal.of(literal)
        val deskLamp = seedImage(images, "desk-lamp", Color(0xF5B700))
        val floorLamp = seedImage(images, "floor-lamp", Color(0x6D5DD3))
        products.create(Product("desk-lamp", "Desk lamp", "LMP-0001", price("24.90"), price("11.00"), 12, true, "lamps", deskLamp))
        products.create(Product("floor-lamp", "Floor lamp", "LMP-0002", price("89.00"), price("42.50"), 3, true, "lamps", floorLamp))
        products.create(Product("stool", "Stool", "CHR-0001", price("35.50"), price("20.00"), 0, true, "chairs"))
        tx.create(resource("customers"), mapOf(Resource.ID to text("cus-1"), "email" to text("ada@example.com"), "name" to text("Ada")))
    }
}

/** Draws a simple lamp in [color] as a PNG, stores it in the `images` bucket, and returns its key. */
private suspend fun seedImage(images: BlobStore, name: String, color: Color): String {
    val image = BufferedImage(160, 160, BufferedImage.TYPE_INT_ARGB)
    image.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        this.color = Color(0xF4F4F5)
        fillRect(0, 0, 160, 160)
        this.color = color
        fillPolygon(intArrayOf(50, 110, 125, 35), intArrayOf(30, 30, 80, 80), 4)
        this.color = Color(0x3F3F46)
        fillRect(76, 80, 8, 50)
        fillRoundRect(45, 128, 70, 10, 6, 6)
        dispose()
    }
    val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    val key = BlobKey.of("images/seed/$name.png")
    images.put(key, Buffer().apply { write(bytes) }, "image/png")
    return key.value
}
