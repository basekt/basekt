package io.github.basekt.testing

import io.github.basekt.data.Comparison
import io.github.basekt.data.Counting
import io.github.basekt.data.DataSource
import io.github.basekt.data.Direction
import io.github.basekt.data.DuplicateIdException
import io.github.basekt.data.Filter
import io.github.basekt.data.InvalidQueryException
import io.github.basekt.data.Query
import io.github.basekt.data.Sort
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import io.github.basekt.schema.schema
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The tests every [DataSource] adapter must pass. Subclass it in the adapter's tests and return a fresh, empty source
 * from [createDataSource]; the adapter may prepare storage for [contractSchema] there.
 */
public abstract class DataSourceContract {
    /** Returns a new, empty data source that can store [contractSchema]'s resources. */
    protected abstract fun createDataSource(): DataSource

    private val groups: Resource = contractSchema.resource("groups")!!
    private val entries: Resource = contractSchema.resource("entries")!!

    private fun text(value: String) = Value.Text(value)
    private fun int(value: Long) = Value.Integer(value)
    private fun Record.id(): String = (this[Resource.ID] as Value.Text).value
    private fun List<Record>.ids(): List<String> = map { it.id() }

    private suspend fun DataSource.seedRanks(vararg ranks: Long?): List<Record> = ranks.mapIndexed { index, rank ->
        create(entries, mapOf(Resource.ID to text("e%02d".format(index)), "rank" to rank?.let(::int)))
    }

    /** A create without an id gets a generated one, and the stored record has every field. */
    @Test
    public fun createGeneratesAnIdAndReturnsEveryField(): Unit = runTest {
        val source = createDataSource()
        val created = source.create(entries, mapOf("title" to text("first")))
        val id = created.id()
        assertTrue(id.isNotEmpty())
        assertEquals(setOf(Resource.ID) + entries.fields.map { it.name }, created.keys)
        assertNull(created["rank"])
        assertEquals(created, source.get(entries, id))
    }

    /** A create may give its own id, and a taken id is refused. */
    @Test
    public fun createKeepsAGivenIdAndRefusesDuplicates(): Unit = runTest {
        val source = createDataSource()
        source.create(entries, mapOf(Resource.ID to text("a"), "title" to text("one")))
        val error = assertFailsWith<DuplicateIdException> { source.create(entries, mapOf(Resource.ID to text("a"))) }
        assertEquals("a", error.id)
        assertEquals(text("one"), source.get(entries, "a")!!["title"])
    }

    /** Every field type comes back as it was stored. */
    @Test
    public fun everyFieldTypeRoundTrips(): Unit = runTest {
        val source = createDataSource()
        val group = source.create(groups, mapOf("label" to text("g")))
        val values: Record = mapOf(
            "title" to text("Ünïcode ✓"),
            "rank" to int(Int.MIN_VALUE.toLong()),
            "size" to int(Long.MAX_VALUE),
            "amount" to Value.Decimal.of("12345678901234567890.0001"),
            "flag" to Value.Bool(true),
            "at" to Value.Timestamp(Instant.parse("2026-03-04T05:06:07.123Z")),
            "kind" to text("b"),
            "groupId" to text(group.id()),
        )
        val created = source.create(entries, values)
        assertEquals(values, source.get(entries, created.id())!! - Resource.ID)
    }

    /** getMany returns the existing records by id and skips missing ones. */
    @Test
    public fun getManyLoadsRecordsById(): Unit = runTest {
        val source = createDataSource()
        source.seedRanks(1, 2, 3)
        val found = source.getMany(entries, listOf("e02", "missing", "e00", "e02"))
        assertEquals(setOf("e00", "e02"), found.keys)
        assertEquals(int(3), found.getValue("e02")["rank"])
        assertEquals(emptyMap(), source.getMany(entries, emptyList()))
        assertEquals(found, source.transaction { it.getMany(entries, listOf("e00", "e02")) })
    }

    /** The same id may exist in two resources. */
    @Test
    public fun resourcesAreSeparate(): Unit = runTest {
        val source = createDataSource()
        source.create(groups, mapOf(Resource.ID to text("x"), "label" to text("g")))
        source.create(entries, mapOf(Resource.ID to text("x")))
        assertEquals(1, source.list(groups).items.size)
        assertEquals(1, source.list(entries).items.size)
        assertNull(source.get(groups, "missing"))
    }

    /** An update changes only the given fields, clears fields set to null, and never changes the id. */
    @Test
    public fun updatePatchesFields(): Unit = runTest {
        val source = createDataSource()
        source.create(entries, mapOf(Resource.ID to text("a"), "title" to text("t"), "rank" to int(1)))
        val updated = source.update(entries, "a", mapOf("rank" to int(2), "title" to null))
        assertNotNull(updated)
        assertEquals(int(2), updated["rank"])
        assertNull(updated["title"])
        assertEquals(updated, source.get(entries, "a"))
        assertNull(source.update(entries, "missing", mapOf("rank" to int(3))))
    }

    /** A delete removes the record and reports whether there was one. */
    @Test
    public fun deleteRemovesTheRecord(): Unit = runTest {
        val source = createDataSource()
        source.create(entries, mapOf(Resource.ID to text("a")))
        assertTrue(source.delete(entries, "a"))
        assertNull(source.get(entries, "a"))
        assertFalse(source.delete(entries, "a"))
    }

    /** Without a sort, records come in id order. */
    @Test
    public fun listWithoutSortIsInIdOrder(): Unit = runTest {
        val source = createDataSource()
        for (id in listOf("c", "a", "b")) source.create(entries, mapOf(Resource.ID to text(id)))
        assertEquals(listOf("a", "b", "c"), source.list(entries).items.ids())
    }

    /** Each comparison operator, and comparisons with nulls, which never match. */
    @Test
    public fun comparisonsFollowSqlNullSemantics(): Unit = runTest {
        val source = createDataSource()
        source.seedRanks(1, 2, 3, null)
        suspend fun ranks(op: Comparison, value: Long) =
            source.list(entries, Query(Filter.Compare("rank", op, int(value)))).items.ids()
        assertEquals(listOf("e01"), ranks(Comparison.EQ, 2))
        assertEquals(listOf("e00", "e02"), ranks(Comparison.NE, 2))
        assertEquals(listOf("e02"), ranks(Comparison.GT, 2))
        assertEquals(listOf("e01", "e02"), ranks(Comparison.GTE, 2))
        assertEquals(listOf("e00"), ranks(Comparison.LT, 2))
        assertEquals(listOf("e00", "e01"), ranks(Comparison.LTE, 2))
        assertEquals(listOf("e03"), source.list(entries, Query(Filter.IsNull("rank"))).items.ids())
        assertEquals(listOf("e00", "e01", "e02"), source.list(entries, Query(Filter.IsNull("rank", isNull = false))).items.ids())
    }

    /** Decimals and instants compare by value, not by text. */
    @Test
    public fun decimalsAndInstantsCompareByValue(): Unit = runTest {
        val source = createDataSource()
        for ((id, amount) in listOf("a" to "9.5", "b" to "10", "c" to "-11")) {
            source.create(entries, mapOf(Resource.ID to text(id), "amount" to Value.Decimal.of(amount)))
        }
        val big = source.list(entries, Query(Filter.Compare("amount", Comparison.GT, Value.Decimal.of("9.75"))))
        assertEquals(listOf("b"), big.items.ids())
        val sorted = source.list(entries, Query(sort = listOf(Sort("amount"))))
        assertEquals(listOf("c", "a", "b"), sorted.items.ids())

        val times = listOf("2026-01-02T00:00:00Z", "2025-12-31T23:59:59Z").map { Value.Timestamp(Instant.parse(it)) }
        source.update(entries, "a", mapOf("at" to times[0]))
        source.update(entries, "b", mapOf("at" to times[1]))
        val after = source.list(entries, Query(Filter.Compare("at", Comparison.GTE, Value.Timestamp(Instant.parse("2026-01-01T00:00:00Z")))))
        assertEquals(listOf("a"), after.items.ids())
    }

    /** In, contains (ignoring case), and, or and not. */
    @Test
    public fun combinedFilters(): Unit = runTest {
        val source = createDataSource()
        val rows = listOf(Triple("a", "Red Apple", "a"), Triple("b", "green apple", "b"), Triple("c", "Banana", "c"))
        for ((id, title, kind) in rows) source.create(entries, mapOf(Resource.ID to text(id), "title" to text(title), "kind" to text(kind)))
        suspend fun ids(filter: Filter) = source.list(entries, Query(filter)).items.ids()
        assertEquals(listOf("a", "c"), ids(Filter.In("kind", listOf(text("a"), text("c")))))
        assertEquals(listOf("a", "b"), ids(Filter.Contains("title", "APPLE")))
        assertEquals(listOf("b"), ids(Filter.And(listOf(Filter.Contains("title", "apple"), Filter.Compare("kind", Comparison.EQ, text("b"))))))
        assertEquals(listOf("a", "c"), ids(Filter.Or(listOf(Filter.Compare(Resource.ID, Comparison.EQ, text("a")), Filter.Compare("kind", Comparison.EQ, text("c"))))))
        assertEquals(listOf("c"), ids(Filter.Not(Filter.Contains("title", "apple"))))
        assertEquals(listOf("a", "b", "c"), ids(Filter.And(emptyList())))
        assertEquals(emptyList(), ids(Filter.Or(emptyList())))
    }

    /** Several sort keys, ties broken by id, nulls last ascending and first descending. */
    @Test
    public fun sortingIsTotalWithNullsLastAscending(): Unit = runTest {
        val source = createDataSource()
        source.seedRanks(2, null, 1, 2)
        assertEquals(listOf("e02", "e00", "e03", "e01"), source.list(entries, Query(sort = listOf(Sort("rank")))).items.ids())
        assertEquals(
            listOf("e01", "e00", "e03", "e02"),
            source.list(entries, Query(sort = listOf(Sort("rank", Direction.DESC)))).items.ids(),
        )
        source.update(entries, "e00", mapOf("title" to text("z")))
        source.update(entries, "e03", mapOf("title" to text("a")))
        val twoKeys = Query(sort = listOf(Sort("rank", Direction.DESC), Sort("title", Direction.ASC)))
        assertEquals(listOf("e01", "e03", "e00", "e02"), source.list(entries, twoKeys).items.ids())
    }

    /** Walking every page returns each matching record once, in order, and the last page has no cursor. */
    @Test
    public fun cursorPaginationWalksEveryRecordOnce(): Unit = runTest {
        val source = createDataSource()
        source.seedRanks(5, 3, null, 3, 9, 1, 3, null, 7, 3, 2)
        for (direction in Direction.entries) {
            val sort = listOf(Sort("rank", direction))
            val filter = Filter.Not(Filter.Compare("rank", Comparison.EQ, int(9)))
            val expected = source.list(entries, Query(filter, sort, limit = 100)).items.ids()
            assertEquals(10, expected.size)

            val walked = mutableListOf<String>()
            var cursor: String? = null
            var pages = 0
            do {
                val page = source.list(entries, Query(filter, sort, limit = 3, cursor = cursor))
                walked += page.items.ids()
                cursor = page.nextCursor
                pages++
            } while (cursor != null)
            assertEquals(expected, walked, "walking $direction")
            assertEquals(4, pages)
        }
    }

    /** A page that is exactly full and the last has no next cursor. */
    @Test
    public fun anExactlyFullLastPageHasNoCursor(): Unit = runTest {
        val source = createDataSource()
        source.seedRanks(1, 2, 3)
        val page = source.list(entries, Query(limit = 3))
        assertEquals(3, page.items.size)
        assertNull(page.nextCursor)
        assertFalse(page.hasNext)
        assertTrue(source.list(entries, Query(limit = 2)).hasNext)
    }

    /** Unknown fields, mistyped values and foreign cursors are refused. */
    @Test
    public fun invalidQueriesAreRefused(): Unit = runTest {
        val source = createDataSource()
        source.seedRanks(1, 2, 3)
        assertFailsWith<InvalidQueryException> { source.list(entries, Query(Filter.IsNull("colour"))) }
        assertFailsWith<InvalidQueryException> { source.list(entries, Query(sort = listOf(Sort("colour")))) }
        assertFailsWith<InvalidQueryException> { source.list(entries, Query(Filter.Compare("rank", Comparison.EQ, text("1")))) }
        assertFailsWith<InvalidQueryException> { source.list(entries, Query(cursor = "not a cursor")) }
        val cursor = source.list(entries, Query(sort = listOf(Sort("rank")), limit = 1)).nextCursor!!
        assertFailsWith<InvalidQueryException> { source.list(entries, Query(sort = listOf(Sort("title")), cursor = cursor)) }
    }

    /** A transaction's writes are visible inside it and committed when it returns. */
    @Test
    public fun transactionsCommit(): Unit = runTest {
        val source = createDataSource()
        val result = source.transaction { tx ->
            tx.create(entries, mapOf(Resource.ID to text("a")))
            tx.update(entries, "a", mapOf("rank" to int(1)))
            tx.get(entries, "a")!!["rank"]
        }
        assertEquals(int(1), result)
        assertEquals(int(1), source.get(entries, "a")!!["rank"])
    }

    /** A transaction that throws leaves no trace, and a failed inner transaction rolls back only itself. */
    @Test
    public fun transactionsRollBack(): Unit = runTest {
        val source = createDataSource()
        source.create(entries, mapOf(Resource.ID to text("kept"), "rank" to int(1)))
        assertFailsWith<IllegalStateException> {
            source.transaction { tx ->
                tx.create(entries, mapOf(Resource.ID to text("dropped")))
                tx.update(entries, "kept", mapOf("rank" to int(2)))
                tx.delete(entries, "kept")
                error("boom")
            }
        }
        assertEquals(listOf("kept"), source.list(entries).items.ids())
        assertEquals(int(1), source.get(entries, "kept")!!["rank"])

        source.transaction { tx ->
            tx.create(entries, mapOf(Resource.ID to text("outer")))
            runCatching {
                tx.transaction { inner ->
                    inner.create(entries, mapOf(Resource.ID to text("inner")))
                    error("inner fails")
                }
            }
        }
        assertEquals(listOf("kept", "outer"), source.list(entries).items.ids())
    }

    /** A source that offers [Counting] counts what [DataSource.list] would return. Other sources skip this test. */
    @Test
    public fun countingMatchesListing(): Unit = runTest {
        val source = createDataSource()
        if (source !is Counting) return@runTest
        source.seedRanks(1, 2, 3, null)
        assertEquals(4, source.count(entries))
        assertEquals(2, source.count(entries, Filter.Compare("rank", Comparison.GTE, int(2))))
        assertEquals(2, source.count(entries, Filter.Not(Filter.Compare("rank", Comparison.GTE, int(2)))))
        assertFailsWith<InvalidQueryException> { source.count(entries, Filter.IsNull("colour")) }
    }

    /** Concurrent creates all land, with distinct ids. */
    @Test
    public fun concurrentCreatesAllLand(): Unit = runTest {
        val source = createDataSource()
        val created = (1..100).map { n -> async { source.create(entries, mapOf("rank" to int(n.toLong()))) } }.awaitAll()
        assertEquals(100, created.ids().toSet().size)
        assertEquals(100, source.list(entries, Query(limit = 1000)).items.size)
    }

    /** The schema the contract runs on: every field type and both relation kinds. */
    public companion object {
        /** Two resources that use every field type: `groups`, and `entries` that belong to a group. */
        public val contractSchema: Schema = schema {
            resource("groups") {
                string("label") { required() }
                hasMany("entries", "entries", foreignField = "groupId")
            }
            resource("entries") {
                string("title")
                int("rank")
                long("size")
                decimal("amount")
                bool("flag")
                instant("at")
                enum("kind", listOf("a", "b", "c"))
                belongsTo("group", "groups")
            }
        }
    }
}
