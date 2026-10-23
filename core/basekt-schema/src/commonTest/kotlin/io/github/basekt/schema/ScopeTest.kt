package io.github.basekt.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScopeTest {
    /** A made-up workshop: jobs belong to a site, and notes are edited only by whoever wrote them. */
    private val workshopSchema = schema {
        resource("sites") {
            string("name") { required() }
            permissions { read(authenticated); scope(Resource.ID, attribute = "site", on = setOf(Operation.READ)) }
        }
        resource("jobs") {
            reference("siteId", "sites") { required() }
            string("title")
            permissions { read(authenticated); write("mechanic"); scope("siteId", attribute = "site", unless = setOf("owner")) }
        }
        resource("notes") {
            string("authorId")
            string("body")
            permissions { read(authenticated); write(authenticated); ownedBy("authorId", on = writes) }
        }
    }

    @Test
    fun `the DSL builds scopes as data`() {
        assertEquals(
            listOf(Scope("siteId", ScopeMatch.Attribute("site"), Operation.entries.toSet(), setOf("owner"))),
            workshopSchema.resource("jobs")!!.permissions.scopes,
        )
        assertEquals(
            listOf(Scope("authorId", ScopeMatch.Caller, setOf(Operation.CREATE, Operation.UPDATE, Operation.DELETE))),
            workshopSchema.resource("notes")!!.permissions.scopes,
        )
    }

    @Test
    fun `scopes survive a JSON round trip and can be written as JSON`() {
        assertEquals(workshopSchema, Schema.fromJson(workshopSchema.toJson()))
        val schema = Schema.fromJson(
            """
            {"resources": [{"name": "rooms", "fields": [{"name": "floor", "type": "string"}],
              "permissions": {"read": {"kind": "authenticated"},
                "scopes": [{"field": "floor", "match": {"kind": "attribute", "name": "floor"}, "operations": ["read"]}]}}]}
            """.trimIndent(),
        )
        assertEquals(
            Scope("floor", ScopeMatch.Attribute("floor"), setOf(Operation.READ)),
            schema.resource("rooms")!!.permissions.scopes.single(),
        )
    }

    @Test
    fun `a scope gives a caller's values, or nothing when it doesn't apply`() {
        val jobs = workshopSchema.resource("jobs")!!.permissions.scopes.single()
        val sites = mapOf("site" to setOf("north", "south"))
        assertEquals(setOf("north", "south"), jobs.valuesFor(Operation.READ, "u1", setOf("mechanic"), sites))
        assertEquals(emptySet(), jobs.valuesFor(Operation.READ, "u1", setOf("mechanic"), emptyMap()))
        assertEquals(emptySet(), jobs.valuesFor(Operation.READ, null, emptySet(), emptyMap()))
        assertNull(jobs.valuesFor(Operation.READ, "u1", setOf("owner"), emptyMap()))

        val notes = workshopSchema.resource("notes")!!.permissions.scopes.single()
        assertEquals(setOf("u1"), notes.valuesFor(Operation.UPDATE, "u1", emptySet(), emptyMap()))
        assertNull(notes.valuesFor(Operation.READ, "u1", emptySet(), emptyMap()))
    }

    @Test
    fun `an invalid scope is refused with its path`() {
        val error = assertFailsWith<InvalidSchemaException> {
            schema {
                resource("jobs") {
                    int("hours")
                    permissions {
                        scope("missing", attribute = "site")
                        scope("hours", attribute = "site")
                        scope(Resource.ID, attribute = " ")
                        scope(Resource.ID, attribute = "site", on = emptySet())
                        ownedBy(Resource.ID, unless = setOf(""))
                    }
                }
            }
        }
        val paths = error.problems.map { it.path }
        for (i in 0..4) assertTrue("resources.jobs.permissions.scopes.$i" in paths, "expected a problem on scope $i in $paths")
    }
}
