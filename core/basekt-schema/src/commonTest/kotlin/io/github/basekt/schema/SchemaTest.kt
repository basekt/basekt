package io.github.basekt.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchemaTest {
    @Test
    fun `the DSL builds fields, relations and permissions as data`() {
        val orders = shopSchema.resource("orders")!!
        assertEquals(
            Field("customerId", FieldType.REFERENCE, required = true, target = "customers"),
            orders.field("customerId"),
        )
        assertEquals(Relation.BelongsTo("customer", "customers", "customerId"), orders.relation("customer"))
        assertEquals(Access.Authenticated, orders.permissions.create)
        assertEquals(Access.Roles(setOf("staff")), orders.permissions.update)
        assertEquals(Access.Nobody, orders.permissions.delete)

        val products = shopSchema.resource("products")!!
        assertEquals(listOf(Rule.Length(1, 120)), products.field("name")!!.rules)
        assertEquals(Access.Roles(setOf("admin")), products.permissions.delete)
        assertEquals(Access.Roles(setOf("staff")), products.permissions.update)
    }

    @Test
    fun `both made-up schemas survive a JSON round trip`() {
        for (schema in listOf(shopSchema, catalogueSchema)) {
            assertEquals(schema, Schema.fromJson(schema.toJson()))
        }
    }

    @Test
    fun `a schema can be written as plain JSON`() {
        val schema = Schema.fromJson(
            """
            {"resources": [
              {"name": "notes",
               "fields": [
                 {"name": "body", "type": "string", "required": true, "rules": [{"kind": "length", "max": 10}]},
                 {"name": "mood", "type": "enum", "values": ["calm", "busy"]}
               ],
               "permissions": {"read": {"kind": "everyone"}, "create": {"kind": "roles", "roles": ["writer"]}}}
            ]}
            """.trimIndent(),
        )
        val notes = schema.resource("notes")!!
        assertEquals(FieldType.ENUM, notes.field("mood")!!.type)
        assertEquals(Access.Everyone, notes.permissions.read)
        assertEquals(Access.Nobody, notes.permissions.delete)
    }

    @Test
    fun `an invalid schema is refused with every problem and its path`() {
        val error = assertFailsWith<InvalidSchemaException> {
            schema {
                resource("books") {
                    string("id")
                    string("title") { min(1) }
                    int("pages") { length(max = 3) }
                    string("code") { pattern("[unclosed") }
                    enum("format", emptyList())
                    reference("shelfId", "shelves")
                    hasMany("reviews", "reviews", foreignField = "bookId")
                }
                resource("books") {}
                resource("2fast") {}
            }
        }
        val paths = error.problems.map { it.path }
        assertTrue("resources.books" in paths)
        assertTrue("resources.2fast" in paths)
        for (field in listOf("id", "title", "pages", "code", "format", "shelfId")) {
            assertTrue("resources.books.fields.$field" in paths, "expected a problem on $field in $paths")
        }
        assertTrue("resources.books.relations.reviews" in paths)
    }

    @Test
    fun `a relation must use a matching reference field`() {
        val error = assertFailsWith<InvalidSchemaException> {
            schema {
                resource("shelves") { hasMany("books", "books", foreignField = "title") }
                resource("books") { string("title") }
            }
        }
        assertEquals(listOf("resources.shelves.relations.books"), error.problems.map { it.path })
    }

    @Test
    fun `access rules tell anonymous, authenticated and role-holding callers apart`() {
        assertTrue(Access.Everyone.allows(null))
        assertFalse(Access.Authenticated.allows(null))
        assertTrue(Access.Authenticated.allows(emptySet()))
        assertFalse(Access.Roles(setOf("staff")).allows(setOf("guest")))
        assertTrue(Access.Roles(setOf("staff", "admin")).allows(setOf("admin")))
        assertFalse(Access.Nobody.allows(setOf("admin")))
    }

    @Test
    fun `missing custom validators are reported`() {
        assertEquals(setOf("isbn"), catalogueSchema.missingValidators(emptyMap()))
        assertEquals(emptySet(), catalogueSchema.missingValidators(mapOf("isbn" to isbnValidator)))
    }
}
