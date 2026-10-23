package io.github.basekt.data

import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
import io.github.basekt.schema.schema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QueryTest {
    private val books = schema {
        resource("books") {
            string("title")
            long("loans")
            enum("format", listOf("paper", "ebook"))
        }
    }.resource("books")!!

    @Test
    fun `a query that fits the resource passes`() {
        books.checkQuery(
            Query(
                filter = Filter.And(
                    listOf(
                        Filter.Compare("loans", Comparison.GT, Value.Integer(3)),
                        Filter.Contains("format", "pa"),
                        Filter.Compare(Resource.ID, Comparison.EQ, Value.Text("b1")),
                    ),
                ),
                sort = listOf(Sort("title"), Sort(Resource.ID, Direction.DESC)),
            ),
        )
    }

    @Test
    fun `unknown fields, mistyped values and searches on numbers are refused`() {
        assertFailsWith<InvalidQueryException> { books.checkQuery(Query(sort = listOf(Sort("author")))) }
        assertFailsWith<InvalidQueryException> {
            books.checkQuery(Query(Filter.Compare("loans", Comparison.EQ, Value.Text("3"))))
        }
        assertFailsWith<InvalidQueryException> { books.checkQuery(Query(Filter.Contains("loans", "3"))) }
        assertFailsWith<IllegalArgumentException> { Query(limit = 0) }
    }

    @Test
    fun `a cursor round-trips and is tied to its sort`() {
        val codec = CursorCodec(books, listOf(Sort("loans", Direction.DESC), Sort("title")))
        val key = CursorKey(listOf(Value.Integer(7), null), "b9")
        assertEquals(key, codec.decode(codec.encode(key)))
        assertFailsWith<InvalidQueryException> { CursorCodec(books, listOf(Sort("loans"))).decode(codec.encode(key)) }
        assertFailsWith<InvalidQueryException> { codec.decode("%%%") }
    }
}
