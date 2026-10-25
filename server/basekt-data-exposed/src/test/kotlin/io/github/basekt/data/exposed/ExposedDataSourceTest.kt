package io.github.basekt.data.exposed

import io.github.basekt.data.Direction
import io.github.basekt.data.DuplicateIdException
import io.github.basekt.data.Filter
import io.github.basekt.data.Query
import io.github.basekt.data.Sort
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
import io.github.basekt.schema.schema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExposedDataSourceTest {
    private val boardsSchema = schema {
        resource("boards") {
            string("name") { required() }
            hasMany("cards", "cards", foreignField = "boardId")
        }
        resource("cards") {
            string("title") { length(max = 80) }
            int("points")
            enum("lane", listOf("todo", "doing", "done"))
            belongsTo("board", "boards")
        }
    }
    private val boards = boardsSchema.resource("boards")!!
    private val cards = boardsSchema.resource("cards")!!
    private val mapping = TableMapping(tablePrefix = "kb_", indexes = listOf(IndexSpec("cards", "points")))

    private fun source(database: Database = h2()) =
        ExposedDataSource(database, boardsSchema, mapping).also { runBlocking { it.createTables() } }

    private fun Record.id() = (this[Resource.ID] as Value.Text).value

    @Test
    fun `resources become prefixed tables with typed columns and indexes`() {
        val database = h2()
        source(database)
        transaction(database) {
            val meta = (connection.connection as Connection).metaData
            val tables = meta.getTables(null, null, "%", arrayOf("TABLE")).use { rows ->
                buildList { while (rows.next()) add(rows.getString("TABLE_NAME").lowercase()) }
            }
            assertTrue(tables.containsAll(listOf("kb_boards", "kb_cards")), "tables: $tables")

            val cardsTable = tables.first { it == "kb_cards" }.let { name -> if (meta.storesUpperCaseIdentifiers()) name.uppercase() else name }
            val columns = meta.getColumns(null, null, cardsTable, "%").use { rows ->
                buildMap { while (rows.next()) put(rows.getString("COLUMN_NAME").lowercase(), rows.getString("TYPE_NAME") to rows.getInt("COLUMN_SIZE")) }
            }
            assertEquals(setOf("id", "title", "points", "lane", "boardid"), columns.keys)
            assertEquals("CHARACTER VARYING" to 80, columns["title"])
            assertEquals("CHARACTER VARYING" to 5, columns["lane"])
            assertEquals("INTEGER", columns["points"]!!.first)

            val indexed = meta.getIndexInfo(null, null, cardsTable, false, false).use { rows ->
                val byIndex = mutableMapOf<String, MutableList<String>>()
                while (rows.next()) {
                    val column = rows.getString("COLUMN_NAME") ?: continue
                    byIndex.getOrPut(rows.getString("INDEX_NAME")) { mutableListOf() } += column.lowercase()
                }
                byIndex.values.toSet()
            }
            assertTrue(listOf("boardid", "id") in indexed, "indexes: $indexed")
            assertTrue(listOf("points", "id") in indexed, "indexes: $indexed")
        }
    }

    @Test
    fun `an index must name fields the resource has`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ExposedDataSource(h2(), boardsSchema, TableMapping(indexes = listOf(IndexSpec("cards", "colour"))))
        }
        assertEquals(
            "The table mapping doesn't fit the schema:\n- An index on 'cards' names the field 'colour', which it doesn't have.",
            error.message,
        )
    }

    @Test
    fun `getMany and list are one query each`() = runBlocking {
        val recording = RecordingH2()
        val source = source(recording.database)
        val created = (1..5).map { source.create(cards, mapOf("points" to Value.Integer(it.toLong()))) }

        val (found, statements) = recording.during { source.getMany(cards, created.take(3).map { it.id() }) }
        assertEquals(3, found.size)
        assertEquals(1, statements.size, "statements: $statements")

        val (page, listed) = recording.during {
            source.list(cards, Query(Filter.IsNull("points", isNull = false), listOf(Sort("points")), limit = 2))
        }
        assertEquals(2, page.items.size)
        assertEquals(1, listed.size, "statements: $listed")
    }

    @Test
    fun `cursor pages stay stable while other calls insert records`() = runBlocking {
        val source = source()
        val original = (0 until 40).map { n ->
            source.create(cards, mapOf(Resource.ID to Value.Text("c%02d".format(n)), "points" to (n % 7).takeIf { it != 3 }?.let { Value.Integer(it.toLong()) }))
        }.map { it.id() }.toSet()
        val sort = listOf(Sort("points", Direction.DESC))

        val walked = mutableListOf<Record>()
        var cursor: String? = null
        var round = 0
        do {
            val page = source.list(cards, Query(sort = sort, limit = 7, cursor = cursor))
            walked += page.items
            cursor = page.nextCursor
            // Writers insert records everywhere in the order, both before and after the cursor, between pages.
            withContext(Dispatchers.IO) {
                (0 until 6).map { n ->
                    async {
                        val points = listOf(0L, 6L, null, 3L)[(n + round) % 4]
                        source.create(cards, mapOf("points" to points?.let(Value::Integer)))
                    }
                }.awaitAll()
            }
            round++
        } while (cursor != null)

        val ids = walked.map { it.id() }
        assertEquals(ids.size, ids.toSet().size, "a record came twice")
        assertTrue(ids.containsAll(original), "an original record was skipped")
        val points = walked.map { (it["points"] as Value.Integer?)?.value }
        // Descending: nulls first, then values from largest to smallest.
        val order = compareBy<Long?> { it != null }.thenByDescending { it }
        assertEquals(points.sortedWith(order), points)
    }

    @Test
    fun `a duplicate id inside a transaction leaves the transaction usable`() = runBlocking {
        val source = source()
        source.create(boards, mapOf(Resource.ID to Value.Text("b1"), "name" to Value.Text("one")))
        source.transaction { tx ->
            assertFailsWith<DuplicateIdException> { tx.create(boards, mapOf(Resource.ID to Value.Text("b1"))) }
            tx.create(boards, mapOf(Resource.ID to Value.Text("b2"), "name" to Value.Text("two")))
        }
        assertEquals(listOf("b1", "b2"), source.list(boards).items.map { it.id() })
    }

    @Test
    fun `search treats LIKE wildcards as text`() = runBlocking {
        val source = source()
        for ((id, title) in listOf("a" to "50% done", "b" to "500 left", "c" to "snake_case")) {
            source.create(cards, mapOf(Resource.ID to Value.Text(id), "title" to Value.Text(title)))
        }
        suspend fun search(text: String) = source.list(cards, Query(Filter.Contains("title", text))).items.map { it.id() }
        assertEquals(listOf("a"), search("50%"))
        assertEquals(listOf("c"), search("e_c"))
    }

    @Test
    fun `references hold ids that relation queries filter on`() = runBlocking {
        val source = source()
        val board = source.create(boards, mapOf("name" to Value.Text("main")))
        val other = source.create(boards, mapOf("name" to Value.Text("other")))
        for (b in listOf(board, board, other)) source.create(cards, mapOf("boardId" to Value.Text(b.id())))
        val onBoard = source.list(cards, Query(Filter.Compare("boardId", io.github.basekt.data.Comparison.EQ, Value.Text(board.id()))))
        assertEquals(2, onBoard.items.size)
        assertEquals(2, source.count(cards, Filter.Compare("boardId", io.github.basekt.data.Comparison.EQ, Value.Text(board.id()))))
    }
}
