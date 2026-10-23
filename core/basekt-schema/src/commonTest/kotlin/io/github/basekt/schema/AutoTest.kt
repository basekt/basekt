package io.github.basekt.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AutoTest {
    /** A made-up greenhouse log: numbered readings, stamped with when and by whom. */
    private val greenhouseSchema = schema {
        resource("readings") {
            string("code") { required(); sequence("R-", digits = 4) }
            long("serial") { sequence(start = 500) }
            decimal("humidity")
            timestamps()
            string("takenBy") { required(); createdBy() }
            reference("checkedBy", "readings") { updatedBy() }
            permissions { read(everyone); write(authenticated) }
        }
    }

    private val readings = greenhouseSchema.resource("readings")!!

    @Test
    fun `the DSL builds automatic fields as data`() {
        assertEquals(Auto.Sequence("R-", 4), readings.field("code")!!.auto)
        assertEquals(Auto.Sequence(start = 500), readings.field("serial")!!.auto)
        assertEquals(Auto.CreatedAt, readings.field("createdAt")!!.auto)
        assertEquals(FieldType.INSTANT, readings.field("updatedAt")!!.type)
        assertEquals(Auto.CreatedBy, readings.field("takenBy")!!.auto)
        assertEquals(Auto.UpdatedBy, readings.field("checkedBy")!!.auto)
        assertEquals(null, readings.field("humidity")!!.auto)
    }

    @Test
    fun `automatic fields survive a JSON round trip`() {
        assertEquals(greenhouseSchema, Schema.fromJson(greenhouseSchema.toJson()))
    }

    @Test
    fun `a sequence formats its number for the field's type`() {
        assertEquals(Value.Text("R-0042"), Auto.Sequence("R-", 4).valueOf(42, FieldType.STRING))
        assertEquals(Value.Text("123456"), Auto.Sequence(digits = 3).valueOf(123456, FieldType.STRING))
        assertEquals(Value.Integer(42), Auto.Sequence().valueOf(42, FieldType.LONG))
    }

    @Test
    fun `clients can't set automatic fields, and required ones aren't demanded of them`() {
        val valid = readings.decodeRecord(json("""{"humidity": "0.4", "createdAt": null}"""), WriteMode.CREATE)
        assertIs<DecodeResult.Valid>(valid)
        assertEquals(mapOf<String, Value?>("humidity" to Value.Decimal.of("0.4")), valid.record)

        val forged = readings.decodeRecord(json("""{"code": "R-9999", "takenBy": "someone"}"""), WriteMode.CREATE)
        assertIs<DecodeResult.Invalid>(forged)
        assertEquals(listOf("code", "takenBy"), forged.errors.map { it.field })
        assertTrue(forged.errors.all { it.code == FieldErrorCode.READ_ONLY })

        // Once present, as after the server or a hook set them, required automatic fields are checked like any other.
        val unset = readings.validateRecord(mapOf("code" to null, "takenBy" to null), WriteMode.CREATE)
        assertEquals(listOf("code", "takenBy"), unset.map { it.field })
    }

    @Test
    fun `an automatic field of the wrong type is refused with its path`() {
        val error = assertFailsWith<InvalidSchemaException> {
            schema {
                resource("readings") {
                    string("at") { createdAt() }
                    int("by") { updatedBy() }
                    bool("number") { sequence() }
                    long("tagged") { sequence(prefix = "T-") }
                    string("wide") { sequence(digits = 40) }
                    string("negative") { sequence(start = -1) }
                }
            }
        }
        val paths = error.problems.map { it.path }
        for (field in listOf("at", "by", "number", "tagged", "wide", "negative")) {
            assertTrue("resources.readings.fields.$field" in paths, "expected a problem on $field in $paths")
        }
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
}
