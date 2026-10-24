package io.github.basekt.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkdownFieldTest {
    private val journal = schema {
        resource("entries") {
            string("title") { required() }
            string("body") { markdown { math(); images(); maxLength(40) } }
            string("note") { markdown() }
        }
    }

    private val entries = journal.resource("entries")!!

    @Test
    fun `a markdown field keeps its options and plain fields have none`() {
        assertEquals(MarkdownOptions(math = true, images = true, maxLength = 40), entries.field("body")!!.markdown)
        assertEquals(MarkdownOptions(), entries.field("note")!!.markdown)
        assertEquals(MarkdownOptions.DEFAULT_MAX_LENGTH, entries.field("note")!!.markdown!!.maxLength)
        assertNull(entries.field("title")!!.markdown)
    }

    @Test
    fun `the length is capped by the schema itself`() {
        fun errors(body: String) = entries.validateRecord(mapOf("title" to Value.Text("a"), "body" to Value.Text(body)), WriteMode.CREATE)
        assertEquals(emptyList(), errors("x".repeat(40)))
        assertEquals(FieldErrorCode.LENGTH, errors("x".repeat(41)).single().code)
    }

    @Test
    fun `options survive the JSON form, and a plain schema is unchanged`() {
        val again = Schema.fromJson(journal.toJson())
        assertEquals(journal, again)
        assertEquals(true, again.resource("entries")!!.field("body")!!.markdown!!.math)
        val plain = schema { resource("things") { string("name") } }
        assertTrue("markdown" !in plain.toJson())
    }

    @Test
    fun `only a string field can be markdown, and the cap must be sensible`() {
        fun problems(build: ResourceBuilder.() -> Unit) = runCatching { schema { resource("things", build) } }.exceptionOrNull()
            .let { (it as? InvalidSchemaException)?.problems.orEmpty().map { p -> p.message } }
        assertTrue(problems { int("n") { markdown() } }.any { "Only a string field can be Markdown" in it })
        assertTrue(problems { string("s") { markdown(); unique() } }.any { "can't be unique" in it })
        assertTrue(problems { string("s") { markdown { maxLength(0) } } }.any { "maxLength must be between" in it })
        assertTrue(problems { string("s") { markdown { maxLength(MarkdownOptions.LIMIT + 1) } } }.any { "maxLength must be between" in it })
    }
}
