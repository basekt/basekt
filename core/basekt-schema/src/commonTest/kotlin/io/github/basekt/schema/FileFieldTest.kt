package io.github.basekt.schema

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileFieldTest {
    // A gallery whose photos are files, and a typed model whose scan is one.
    private val gallery = schema {
        resource("photos") {
            string("caption")
            file("image", "pictures") { required() }
        }
    }

    @Serializable
    data class Letter(val id: String? = null, val subject: String, val scan: String? = null)

    @Test
    fun `a file field holds a key in its bucket`() {
        val photos = gallery.resource("photos")!!
        val image = photos.field("image")!!
        assertEquals(FieldType.FILE, image.type)
        assertEquals("pictures", image.bucket)

        val valid = photos.decodeRecord(Json.parseToJsonElement("""{"image": "pictures/ab12/sunset.jpg"}""").jsonObject, WriteMode.CREATE)
        assertEquals(Value.Text("pictures/ab12/sunset.jpg"), assertIs<DecodeResult.Valid>(valid).record["image"])

        for (bad in listOf("other/ab12/sunset.jpg", "pictures", "pictures/../x", "/pictures/a.jpg", "pictures/a b.jpg")) {
            val result = photos.decodeRecord(Json.parseToJsonElement("""{"image": "$bad"}""").jsonObject, WriteMode.CREATE)
            val error = assertIs<DecodeResult.Invalid>(result, bad).errors.single()
            assertEquals(FieldErrorCode.TYPE, error.code, bad)
            assertTrue(error.message.contains("'pictures' bucket"), error.message)
        }
    }

    @Test
    fun `a file field round-trips as schema JSON`() {
        assertEquals(gallery, Schema.fromJson(gallery.toJson()))
        assertTrue(gallery.toJson().contains("\"bucket\":\"pictures\""))
    }

    @Test
    fun `typed models declare file fields on String properties`() {
        val letters = model<Letter>("letters") { file(Letter::scan, "scans") }
        val scan = letters.resource.field("scan")!!
        assertEquals(FieldType.FILE, scan.type)
        assertEquals("scans", scan.bucket)
        assertFailsWith<IllegalArgumentException> { model<Letter>("letters") { file(Letter::subject, "../x") }.let { schema { resource(it) } } }
    }

    @Test
    fun `the checker wants a bucket on file fields only`() {
        val noBucket = Schema(listOf(Resource("photos", listOf(Field("image", FieldType.FILE)))))
        assertTrue(noBucket.problems().any { it.message.contains("needs a bucket") })
        val badBucket = Schema(listOf(Resource("photos", listOf(Field("image", FieldType.FILE, bucket = "../up")))))
        assertTrue(badBucket.problems().isNotEmpty())
        val stray = Schema(listOf(Resource("photos", listOf(Field("caption", FieldType.STRING, bucket = "pictures")))))
        assertTrue(stray.problems().any { it.message.contains("Only a file field has a bucket") })
    }
}
