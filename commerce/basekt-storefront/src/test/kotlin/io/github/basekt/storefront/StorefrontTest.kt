package io.github.basekt.storefront

import io.github.basekt.data.DataSource
import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import io.github.basekt.schema.schema
import io.github.basekt.server.BaseKt
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A made-up bakery: loaves by baker, sold in slices, with a cost price shoppers must never see. */
private val bakery = schema {
    resource("bakers") {
        string("name") { required() }
    }
    resource("loaves") {
        belongsTo("baker", "bakers") { required() }
        string("code") { required(); unique() }
        string("name") { required() }
        string("blurb")
        string("photo")
        decimal("price") { required() }
        decimal("costPrice")
        string("secretRecipe") { hidden() }
        enum("kind", listOf("sourdough", "rye", "sweet")) { required() }
        bool("baked")
        hasMany("slices", "slices", foreignField = "loafId")
        storefront {
            slug("code")
            title("name"); price("price"); image("photo"); summary("blurb")
            filters("bakerId", "kind")
            variants("slices", by = "label")
            listWhen { eq("baked", true) }
        }
    }
    resource("slices") {
        belongsTo("loaf", "loaves", field = "loafId") { required() }
        string("label") { required() }
    }
}

/** A made-up nursery: plants for sale, named by id, with no variants. */
private val nursery = schema {
    resource("plants") {
        string("name") { required() }
        int("pots")
        storefront { title("name"); price("pots") }
    }
}

private fun text(value: String) = Value.Text(value)

private suspend fun DataSource.seedBakery(): Map<String, String> {
    fun id(record: Map<String, Value?>) = (record[Resource.ID] as Value.Text).value
    val loaves = bakery.resource("loaves")!!
    val slices = bakery.resource("slices")!!
    val ana = id(create(bakery.resource("bakers")!!, mapOf("name" to text("Ana"))))
    val ben = id(create(bakery.resource("bakers")!!, mapOf("name" to text("Ben"))))
    fun loaf(code: String, name: String, baker: String, price: String, kind: String, baked: Boolean) = mapOf(
        "bakerId" to text(baker), "code" to text(code), "name" to text(name), "price" to Value.Decimal.of(price),
        "costPrice" to Value.Decimal.of("1.5"), "secretRecipe" to text("30 hours"), "kind" to text(kind),
        "blurb" to text("Crusty."), "photo" to text("https://example.test/$code.jpg"), "baked" to Value.Bool(baked),
    )
    val country = id(create(loaves, loaf("country", "Country loaf", ana, "6.50", "sourdough", true)))
    create(loaves, loaf("dark", "Dark rye", ben, "8", "rye", true))
    create(loaves, loaf("cinnamon", "Cinnamon <script>alert(1)</script>", ana, "5.25", "sweet", true))
    create(loaves, loaf("raw", "Unbaked dough", ana, "1", "sourdough", false))
    val half = id(create(slices, mapOf("loafId" to text(country), "label" to text("half"))))
    val whole = id(create(slices, mapOf("loafId" to text(country), "label" to text("whole"))))
    return mapOf("ana" to ana, "ben" to ben, "half" to half, "whole" to whole, "country" to country)
}

private fun shop(
    schema: Schema,
    seed: suspend DataSource.() -> Unit,
    configure: BaseKtStorefrontConfig.() -> Unit = {},
    test: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    val source = InMemoryDataSource()
    runBlocking { source.seed() }
    application {
        install(BaseKt) {
            dataSource = source
            this.schema = schema
        }
        install(BaseKtStorefront) { configure() }
    }
    test()
}

private fun String.json(): JsonObject = Json.parseToJsonElement(this).jsonObject

class StorefrontTest {
    @Test
    fun `the API lists only the declared fields of the records that may be listed`() = shop(bakery, { seedBakery() }) {
        val body = client.get("/shop/api/loaves").bodyAsText().json()
        val items = body["items"]!!.jsonArray
        assertEquals(setOf("Cinnamon <script>alert(1)</script>", "Country loaf", "Dark rye"), items.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet())
        val country = items.first { it.jsonObject["code"]!!.jsonPrimitive.content == "country" }.jsonObject
        assertEquals("country", country["slug"]!!.jsonPrimitive.content)
        assertEquals("6.5", country["price"]!!.jsonPrimitive.content)
        assertEquals(setOf("slug", "id", "code", "name", "price", "photo", "blurb", "bakerId", "kind"), country.keys)
        assertFalse("costPrice" in country || "secretRecipe" in country || "baked" in country)
    }

    @Test
    fun `listings filter, search, sort and page`() = shop(bakery, { seedBakery() }) {
        fun names(path: String) = runBlocking {
            client.get(path).bodyAsText().json()["items"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content }
        }
        assertEquals(listOf("dark"), names("/shop/api/loaves?kind=rye"))
        assertEquals(listOf("dark", "country", "cinnamon"), names("/shop/api/loaves?sort=-price"))
        assertEquals(listOf("cinnamon", "country", "dark"), names("/shop/api/loaves?sort=price"))
        assertEquals(listOf("country"), names("/shop/api/loaves?q=Country"))
        val first = client.get("/shop/api/loaves?limit=2&sort=price").bodyAsText().json()
        assertEquals(2, first["items"]!!.jsonArray.size)
        val cursor = first["nextCursor"]!!.jsonPrimitive.content
        val second = client.get("/shop/api/loaves?limit=2&sort=price&cursor=$cursor").bodyAsText().json()
        assertEquals(listOf("dark"), second["items"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content })
    }

    @Test
    fun `a filter by reference uses the record's id`() = shop(bakery, { seedBakery() }) {
        val all = client.get("/shop/api/loaves").bodyAsText().json()["items"]!!.jsonArray
        val ana = all.first { it.jsonObject["code"]!!.jsonPrimitive.content == "country" }.jsonObject["bakerId"]!!.jsonPrimitive.content
        val byAna = client.get("/shop/api/loaves?bakerId=$ana").bodyAsText().json()["items"]!!.jsonArray
        assertEquals(setOf("country", "cinnamon"), byAna.map { it.jsonObject["code"]!!.jsonPrimitive.content }.toSet())
    }

    @Test
    fun `bad requests are refused with a message`() = shop(bakery, { seedBakery() }) {
        for (path in listOf("/shop/api/loaves?sort=cost", "/shop/api/loaves?limit=x", "/shop/api/loaves?limit=0")) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.BadRequest, response.status, path)
            assertNotNull(response.bodyAsText().json()["error"])
        }
        // A field that isn't a filter is ignored, so it can't be used to probe hidden data.
        val probe = client.get("/shop/api/loaves?costPrice=1.5").bodyAsText().json()["items"]!!.jsonArray
        assertEquals(3, probe.size)
    }

    @Test
    fun `unknown resources and records are 404, and resources without a storefront are not sold`() = shop(bakery, { seedBakery() }) {
        assertEquals(HttpStatusCode.NotFound, client.get("/shop/api/bakers").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/shop/api/slices").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/shop/api/loaves/nope").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/shop/api/loaves/raw").status, "unbaked loaves aren't listed, nor reachable")
        assertEquals(HttpStatusCode.NotFound, client.get("/shop/bakers").status)
    }

    @Test
    fun `a detail shows variants, and sold out ones are marked by the availability`() {
        lateinit var ids: Map<String, String>
        shop(
            bakery,
            { ids = seedBakery() },
            configure = {
                availability = Availability { _, variants ->
                    variants.filter { (it["label"] as Value.Text).value == "half" }.map { (it[Resource.ID] as Value.Text).value }.toSet()
                }
            },
        ) {
            val detail = client.get("/shop/api/loaves/country").bodyAsText().json()
            val variants = detail["variants"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("half", "whole"), variants.map { it["label"]!!.jsonPrimitive.content })
            assertEquals(listOf(true, false), variants.map { it["available"]!!.jsonPrimitive.boolean })
            assertEquals(ids["half"], variants.first()["id"]!!.jsonPrimitive.content)
            assertEquals("Country loaf", detail["name"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `pages are HTML, escape what records hold and set safe headers`() = shop(bakery, { seedBakery() }, configure = { siteName = "Crumb"; currency = "$" }) {
        val index = client.get("/shop")
        assertEquals(HttpStatusCode.OK, index.status)
        assertTrue(index.bodyAsText().contains("Crumb") && index.bodyAsText().contains("/shop/loaves"))

        val listing = client.get("/shop/loaves")
        val html = listing.bodyAsText()
        assertTrue(html.contains("Country loaf") && html.contains("$6.5"))
        assertFalse(html.contains("<script>alert(1)</script>"), "record text is escaped")
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertTrue(html.contains("<option value=\"sourdough\">"), "enum filters offer their values")
        assertTrue(html.contains(">Ana</option>") && html.contains(">Ben</option>"), "reference filters offer the target's names")
        assertTrue(listing.headers["Content-Security-Policy"]!!.contains("default-src 'none'"))
        assertEquals("nosniff", listing.headers["X-Content-Type-Options"])

        val detail = client.get("/shop/loaves/country").bodyAsText()
        assertTrue(detail.contains("Country loaf") && detail.contains("half") && detail.contains("whole"))
        assertEquals(HttpStatusCode.NotFound, client.get("/shop/loaves/nope").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/shop/nothing").status)
    }

    @Test
    fun `css is appended to the default styles, and can't close them`() = shop(nursery, {}, configure = { css = ".card{border-radius:0}\nh1{color:var(--accent)}" }) {
        val html = client.get("/shop/plants").bodyAsText()
        assertTrue(html.contains(".card{border-radius:0}\nh1{color:var(--accent)}\n</style>"), "the app's css comes last, so it wins")
        assertTrue(html.indexOf(".card{border-radius:0}") > html.indexOf("--accent-fg"))
        val refused = runCatching {
            testApplication {
                application {
                    install(BaseKt) { dataSource = InMemoryDataSource(); this.schema = nursery }
                    install(BaseKtStorefront) { css = "a{}</STYLE><script>x</script>" }
                }
                startApplication()
            }
        }.exceptionOrNull()
        assertTrue(generateSequence(refused) { it.cause }.any { "can't contain '</style'" in it.message.orEmpty() })
    }

    @Test
    fun `an app's own pages replace the default ones, and can reuse the frame`() {
        class Mine : StorefrontPages {
            override fun index(context: StorefrontContext, resources: List<Resource>) = "index of ${resources.size} at ${context.base}"

            override fun listing(context: StorefrontContext, listing: Listing) =
                "<ol>" + listing.items.joinToString("") { "<li>${escapeHtml(it.values["name"].toString())}</li>" } + "</ol>"

            override fun detail(context: StorefrontContext, detail: Detail) =
                DefaultPages.shell(context, "Mine", "<h1 class=\"mine\">${escapeHtml(detail.item.values["name"].toString())}</h1>")
        }
        var plant = ""
        shop(
            nursery,
            { plant = (create(nursery.resource("plants")!!, mapOf("name" to text("<Fern>"), "pots" to Value.Integer(12)))[Resource.ID] as Value.Text).value },
            configure = { pages = Mine(); contentSecurityPolicy = "default-src 'self'" },
        ) {
            assertEquals("index of 1 at /shop", client.get("/shop").bodyAsText())
            val listing = client.get("/shop/plants")
            assertEquals("<ol><li>&lt;Fern&gt;</li></ol>", listing.bodyAsText())
            assertEquals("default-src 'self'", listing.headers["Content-Security-Policy"])
            val detail = client.get("/shop/plants/$plant").bodyAsText()
            assertTrue(detail.contains("<h1 class=\"mine\">&lt;Fern&gt;</h1>") && detail.contains("--accent"), "the shell brings the default frame")
            assertEquals("<Fern>", client.get("/shop/api/plants").bodyAsText().json()["items"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `a record is named by its id when no slug is set`() {
        var plant = ""
        shop(nursery, { plant = (create(nursery.resource("plants")!!, mapOf("name" to text("Fern"), "pots" to Value.Integer(12)))[Resource.ID] as Value.Text).value }) {
            val list = client.get("/shop/api/plants").bodyAsText().json()["items"]!!.jsonArray
            assertEquals(plant, list.single().jsonObject["slug"]!!.jsonPrimitive.content)
            val detail = client.get("/shop/api/plants/$plant").bodyAsText().json()
            assertEquals("Fern", detail["name"]!!.jsonPrimitive.content)
            assertEquals(JsonArray(emptyList()), detail["variants"])
        }
    }

    @Test
    fun `too many requests from one client are refused until the window ends`() = shop(nursery, {}, configure = { rateRequests = 3 }) {
        repeat(3) { assertEquals(HttpStatusCode.OK, client.get("/shop/api/plants").status) }
        val refused = client.get("/shop/api/plants")
        assertEquals(HttpStatusCode.TooManyRequests, refused.status)
        assertNotNull(refused.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun `the limiter counts per client and starts again after its window`() {
        var now = 0L
        val limiter = RateLimiter(2, 1000L) { now }
        assertNull(limiter.retryAfterSeconds("a"))
        assertNull(limiter.retryAfterSeconds("a"))
        assertEquals(1L, limiter.retryAfterSeconds("a"))
        assertNull(limiter.retryAfterSeconds("b"))
        now = 1000L
        assertNull(limiter.retryAfterSeconds("a"))
        assertNull(RateLimiter(0, 1000L) { now }.retryAfterSeconds("a"))
    }

    @Test
    fun `a storefront that doesn't fit its resource is described, and the plugin refuses it`() {
        assertTrue(schema { resource("shoes") { string("name") } }.storefrontProblems().isEmpty(), "no storefront, no problems")

        val wrong = schema {
            resource("shoes") {
                string("name"); string("secret") { hidden() }; bool("flag"); long("size")
                hasMany("sizes", "sizes", foreignField = "shoeId")
                storefront {
                    title("missing"); price("name"); summary("secret"); image("flag"); filters("size", "nope", "shop")
                    variants("sizes", by = "nope"); listWhen { eq("ghost", 1) }
                }
            }
            resource("sizes") { belongsTo("shoe", "shoes", field = "shoeId") { required() } }
        }
        val problems = wrong.storefrontProblems()
        assertTrue(problems.any { "title 'missing' is not a field" in it }, problems.toString())
        assertTrue(problems.any { "price 'name' is a string" in it })
        assertTrue(problems.any { "summary 'secret' is hidden" in it })
        assertTrue(problems.any { "image 'flag'" in it })
        assertTrue(problems.any { "filter 'nope' is not a field" in it })
        assertTrue(problems.any { "listWhen names 'ghost'" in it })
        assertTrue(problems.any { "variants field 'nope'" in it })

        val live = schema { resource("stalls") { provided(); string("name"); permissions { read(everyone) }; storefront { title("name") } } }
        assertTrue(live.storefrontProblems().any { "'stalls' is provided by the application's code" in it }, live.storefrontProblems().toString())

        val error = runCatching {
            testApplication {
                application {
                    install(BaseKt) { dataSource = InMemoryDataSource(); this.schema = wrong }
                    install(BaseKtStorefront)
                }
                startApplication()
            }
        }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(generateSequence(error) { it.cause }.any { "storefronts are not valid" in it.message.orEmpty() })
    }

    @Test
    fun `a storefront needs a title, and its settings come back from the schema's JSON`() {
        val failure = runCatching { schema { resource("things") { string("name"); storefront { price("name") } } } }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException && "title" in failure.message.orEmpty())
        val again = Schema.fromJson(bakery.toJson())
        assertEquals(bakery.resource("loaves")!!.storefront, again.resource("loaves")!!.storefront)
        assertEquals("code", again.resource("loaves")!!.storefront!!.slug)
    }
}
