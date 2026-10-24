package io.github.basekt.schema

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A made-up module's settings, kept on a resource under its own key. */
@Serializable
private data class Shelf(val label: String, val capacity: Int = 10)

@Serializable
private data class Crate(val id: String? = null, val weight: Long)

private fun Extensible.shelf(label: String, capacity: Int = 10) = extension("warehouse.shelf", Shelf(label, capacity))

class ExtensionTest {
    private val depot = schema {
        resource("boxes") {
            long("weight")
            shelf("A", capacity = 4)
        }
        resource("pallets") { long("weight") }
    }

    private val typed = model<Crate>("crates") { shelf("B") }

    @Test
    fun `a module keeps settings on a resource under its own key and reads them back`() {
        assertEquals(Shelf("A", 4), depot.resource("boxes")!!.extension<Shelf>("warehouse.shelf"))
        assertNull(depot.resource("pallets")!!.extension<Shelf>("warehouse.shelf"))
        assertNull(depot.resource("boxes")!!.extension<Shelf>("warehouse.other"))
    }

    @Test
    fun `typed models carry settings too`() {
        assertEquals(Shelf("B"), typed.resource.extension<Shelf>("warehouse.shelf"))
    }

    @Test
    fun `settings survive the JSON form and a schema without them is unchanged`() {
        val again = Schema.fromJson(depot.toJson())
        assertEquals(depot, again)
        assertEquals(Shelf("A", 4), again.resource("boxes")!!.extension<Shelf>("warehouse.shelf"))
        assertFalse(again.resource("pallets")!!.let { "extensions" in Schema(listOf(it)).toJson() })
    }

    @Test
    fun `a later setting under the same key replaces the earlier one`() {
        val replaced = schema { resource("boxes") { long("weight"); shelf("A"); shelf("Z", 1) } }
        assertEquals(Shelf("Z", 1), replaced.resource("boxes")!!.extension<Shelf>("warehouse.shelf"))
    }

    @Test
    fun `the schema checks only the key`() {
        val bad = Schema(listOf(Resource("boxes", extensions = mapOf("1st" to JsonPrimitive(1), "ok-key.v2" to JsonPrimitive(2)))))
        val problems = bad.problems()
        assertEquals(listOf("resources.boxes.extensions.1st"), problems.map { it.path })
        assertTrue(problems.single().message.contains("extension key"))
    }
}
