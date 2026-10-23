package io.github.basekt.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ActionTest {
    /** A made-up kennel: dogs are walked by a named walker, and the whole kennel can be fed at once. */
    private val kennelSchema = schema {
        resource("dogs") {
            string("name") { required() }
            int("walks")
            action("walk") {
                input {
                    string("walker") { required(); length(max = 40) }
                    enum("pace", listOf("slow", "brisk"))
                }
                allow("keeper")
                confirm("Take this dog out?")
            }
            action("feedAll", target = ActionTarget.RESOURCE) { allow(authenticated) }
            permissions { read(everyone); write("keeper") }
        }
    }

    private val dogs = kennelSchema.resource("dogs")!!

    @Test
    fun `the DSL builds actions as data`() {
        val walk = dogs.action("walk")!!
        assertEquals(ActionTarget.RECORD, walk.target)
        assertEquals(listOf("walker", "pace"), walk.input.map { it.name })
        assertEquals(listOf(Rule.Length(max = 40)), walk.input.first().rules)
        assertEquals(Access.Roles(setOf("keeper")), walk.access)
        assertEquals("Take this dog out?", walk.confirm)
        assertEquals(Action("feedAll", ActionTarget.RESOURCE, access = Access.Authenticated), dogs.action("feedAll"))
    }

    @Test
    fun `actions survive a JSON round trip`() {
        assertEquals(kennelSchema, Schema.fromJson(kennelSchema.toJson()))
    }

    @Test
    fun `an action's input decodes and checks like a record`() {
        val input = dogs.action("walk")!!.inputResource
        val missing = input.decodeRecord(kotlinx.serialization.json.buildJsonObject {}, WriteMode.CREATE)
        assertEquals(listOf("walker"), (missing as DecodeResult.Invalid).errors.map { it.field })
    }

    @Test
    fun `an invalid action is refused with its path`() {
        val error = assertFailsWith<InvalidSchemaException> {
            schema {
                resource("dogs") {
                    action("1bad") {}
                    action("walk") { input { string("at") { createdAt() } } }
                    action("walk") { confirm(" ") }
                    action("feed") { input { reference("bowlId", "bowls") } }
                }
            }
        }
        val paths = error.problems.map { it.path }
        for (path in listOf("actions.1bad", "actions.walk", "actions.walk.input.at", "actions.feed.input.bowlId")) {
            assertTrue("resources.dogs.$path" in paths, "expected a problem at $path in $paths")
        }
    }
}
