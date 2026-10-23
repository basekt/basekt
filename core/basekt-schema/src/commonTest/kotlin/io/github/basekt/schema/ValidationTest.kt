package io.github.basekt.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class ValidationTest {
    private val products = shopSchema.resource("products")!!
    private val orders = shopSchema.resource("orders")!!
    private val books = catalogueSchema.resource("books")!!
    private val validators = mapOf("isbn" to isbnValidator)

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun Resource.errors(text: String, mode: WriteMode = WriteMode.CREATE): Map<String, FieldErrorCode> =
        when (val result = decodeRecord(json(text), mode, validators)) {
            is DecodeResult.Valid -> emptyMap()
            is DecodeResult.Invalid -> result.errors.associate { it.field to it.code }
        }

    @Test
    fun `a valid record decodes to typed values`() {
        val result = assertIs<DecodeResult.Valid>(
            products.decodeRecord(json("""{"id": "p1", "name": "Lamp", "price": "12.50", "stock": 3, "published": true}"""), WriteMode.CREATE),
        )
        assertEquals(
            mapOf(
                "id" to Value.Text("p1"),
                "name" to Value.Text("Lamp"),
                "price" to Value.Decimal.of("12.5"),
                "stock" to Value.Integer(3),
                "published" to Value.Bool(true),
            ),
            result.record,
        )
    }

    @Test
    fun `each broken field gets one typed error`() {
        assertEquals(
            mapOf(
                "name" to FieldErrorCode.LENGTH,
                "price" to FieldErrorCode.MIN,
                "stock" to FieldErrorCode.TYPE,
                "colour" to FieldErrorCode.UNKNOWN_FIELD,
            ),
            products.errors("""{"name": "", "price": 0, "stock": "many", "colour": "red"}"""),
        )
        assertEquals(
            mapOf("customerId" to FieldErrorCode.REQUIRED, "status" to FieldErrorCode.ENUM, "placedAt" to FieldErrorCode.TYPE),
            orders.errors("""{"status": "lost", "placedAt": "yesterday"}"""),
        )
    }

    @Test
    fun `patterns and custom validators report their messages`() {
        val customers = shopSchema.resource("customers")!!
        val invalid = assertIs<DecodeResult.Invalid>(customers.decodeRecord(json("""{"email": "nobody"}"""), WriteMode.CREATE))
        assertEquals(FieldError("email", FieldErrorCode.PATTERN, "Must be an email address."), invalid.errors.single())

        assertEquals(mapOf("isbn" to FieldErrorCode.CUSTOM), books.errors("""{"title": "Dune", "isbn": "123"}"""))
        assertEquals(emptyMap(), books.errors("""{"title": "Dune", "isbn": "978-0-441-17271-9"}"""))
    }

    @Test
    fun `an update checks only the fields it sets and can't change the id`() {
        assertEquals(emptyMap(), products.errors("""{"stock": 4}""", WriteMode.UPDATE))
        assertEquals(
            mapOf("id" to FieldErrorCode.READ_ONLY, "name" to FieldErrorCode.REQUIRED),
            products.errors("""{"id": "p2", "name": null}""", WriteMode.UPDATE),
        )
    }

    @Test
    fun `int fields reject values outside 32 bits and long fields accept them`() {
        assertEquals(mapOf("stock" to FieldErrorCode.TYPE), products.errors("""{"name": "a", "price": 1, "stock": 3000000000}"""))
        assertEquals(emptyMap(), books.errors("""{"title": "a", "loans": 3000000000}"""))
    }

    @Test
    fun `records encode back to JSON with the id first`() {
        val record = mapOf(
            "placedAt" to Value.Timestamp(Instant.parse("2026-01-31T12:00:00Z")),
            "status" to Value.Text("open"),
            "id" to Value.Text("o1"),
            "customerId" to null,
        )
        assertEquals(
            """{"id":"o1","customerId":null,"status":"open","placedAt":"2026-01-31T12:00:00Z"}""",
            orders.encodeRecord(record).toString(),
        )
    }

    @Test
    fun `query text parses to the field's type`() {
        assertEquals(Value.Integer(5), products.field("stock")!!.parse("5"))
        assertEquals(null, products.field("stock")!!.parse("five"))
        assertEquals(Value.Bool(false), products.field("published")!!.parse("false"))
        assertEquals(Value.Decimal.of("2"), products.field("price")!!.parse("2.000"))
    }

    @Test
    fun `decimals are exact and ordered`() {
        assertEquals("0", Value.Decimal.of("-0.000").literal)
        assertEquals("12.5", Value.Decimal.of("0012.500").literal)
        val sorted = listOf("10", "-2.5", "9.99", "-10", "0.001", "9.990001").map(Value.Decimal::of).sorted()
        assertEquals(listOf("-10", "-2.5", "0.001", "9.99", "9.990001", "10"), sorted.map { it.literal })
        assertTrue(Value.Decimal.parseOrNull("1e5") == null)
    }
}
