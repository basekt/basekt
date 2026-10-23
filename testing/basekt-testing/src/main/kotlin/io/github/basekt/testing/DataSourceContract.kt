package io.github.basekt.testing

import io.github.basekt.data.AggregateFunction
import io.github.basekt.data.AggregateQuery
import io.github.basekt.data.Aggregating
import io.github.basekt.data.Comparison
import io.github.basekt.data.ConditionalUpdate
import io.github.basekt.data.ConditionalWrites
import io.github.basekt.data.Counting
import io.github.basekt.data.DataSource
import io.github.basekt.data.Direction
import io.github.basekt.data.DuplicateIdException
import io.github.basekt.data.Filter
import io.github.basekt.data.InvalidQueryException
import io.github.basekt.data.Measure
import io.github.basekt.data.Query
import io.github.basekt.data.Repository
import io.github.basekt.data.Sequencing
import io.github.basekt.data.Sort
import io.github.basekt.schema.Model
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.TimeBucket
import io.github.basekt.schema.Value
import io.github.basekt.schema.model
import io.github.basekt.schema.schema
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
            "attachment" to text("docs/3f1c2a/report-2026.pdf"),
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

    /** A source that offers [Sequencing] starts each counter at its start and counts up by one. Others skip this. */
    @Test
    public fun sequencesCountUpFromTheirStart(): Unit = runTest {
        val source = createDataSource()
        if (source !is Sequencing) return@runTest
        assertEquals(listOf(1L, 2L, 3L), List(3) { source.nextValue("contract.a") })
        assertEquals(100, source.nextValue("contract.b", start = 100))
        assertEquals(4, source.nextValue("contract.a", start = 100))
        assertEquals(101, source.transaction { tx -> (tx as Sequencing).nextValue("contract.b") })
    }

    /** A number taken in a transaction that rolls back, whole or nested, is given back. */
    @Test
    public fun sequencesRollBackWithTheirTransaction(): Unit = runTest {
        val source = createDataSource()
        if (source !is Sequencing) return@runTest
        assertEquals(1, source.nextValue("contract.c"))
        assertFailsWith<IllegalStateException> {
            source.transaction { tx ->
                assertEquals(2, (tx as Sequencing).nextValue("contract.c"))
                error("rolls back")
            }
        }
        val taken = source.transaction { tx ->
            runCatching {
                tx.transaction { inner ->
                    (inner as Sequencing).nextValue("contract.c")
                    error("inner rolls back")
                }
            }
            (tx as Sequencing).nextValue("contract.c")
        }
        assertEquals(2, taken)
        assertEquals(3, source.nextValue("contract.c"))
    }

    /** Concurrent transactions never take the same number, and none is skipped. */
    @Test
    public fun sequencesNeverRepeat(): Unit = runTest {
        val source = createDataSource()
        if (source !is Sequencing) return@runTest
        source.nextValue("contract.d")
        val taken = List(20) { async { source.transaction { tx -> (tx as Sequencing).nextValue("contract.d") } } }.awaitAll()
        assertEquals((2L..21L).toList(), taken.sorted())
    }

    /** A conditional update writes only a record that matches, and tells a failed condition from a missing record. */
    @Test
    public fun conditionalUpdatesCheckTheirCondition(): Unit = runTest {
        val source = createDataSource()
        if (source !is ConditionalWrites) return@runTest
        source.create(entries, mapOf(Resource.ID to text("e1"), "title" to text("draft"), "rank" to int(3)))
        val atLeastTwo = Filter.Compare("rank", Comparison.GTE, int(2))

        val updated = source.updateIf(entries, "e1", atLeastTwo, mapOf("title" to text("final")), mapOf("rank" to int(-2)))
        assertEquals(ConditionalUpdate.Updated::class, updated::class)
        assertEquals(text("final"), (updated as ConditionalUpdate.Updated).record["title"])
        assertEquals(int(1), updated.record["rank"])

        assertEquals(ConditionalUpdate.ConditionFailed, source.updateIf(entries, "e1", atLeastTwo, increments = mapOf("rank" to int(-1))))
        assertEquals(int(1), source.get(entries, "e1")!!["rank"])
        assertEquals(ConditionalUpdate.NotFound, source.updateIf(entries, "missing", null, mapOf("title" to text("x"))))
        assertEquals(ConditionalUpdate.Updated::class, source.updateIf(entries, "e1", atLeastTwo.copy(op = Comparison.LT))::class)
        assertFailsWith<InvalidQueryException> { source.updateIf(entries, "e1", null, increments = mapOf("title" to int(1))) }
        assertFailsWith<InvalidQueryException> {
            source.updateIf(entries, "e1", null, changes = mapOf("rank" to int(5)), increments = mapOf("rank" to int(1)))
        }
    }

    /** Increments add to int, long and decimal fields, and leave a null field null. */
    @Test
    public fun conditionalUpdatesIncrementNumbers(): Unit = runTest {
        val source = createDataSource()
        if (source !is ConditionalWrites) return@runTest
        source.create(entries, mapOf(Resource.ID to text("e1"), "rank" to int(10), "size" to int(5), "amount" to Value.Decimal.of("1.25")))
        source.create(entries, mapOf(Resource.ID to text("e2")))
        val bumped = source.updateIf(
            entries, "e1", null,
            increments = mapOf("rank" to int(-3), "size" to int(1_000_000_000_000), "amount" to Value.Decimal.of("0.75")),
        ) as ConditionalUpdate.Updated
        assertEquals(int(7), bumped.record["rank"])
        assertEquals(int(1_000_000_000_005), bumped.record["size"])
        assertEquals(Value.Decimal.of("2"), bumped.record["amount"])
        val unset = source.updateIf(entries, "e2", null, increments = mapOf("rank" to int(1))) as ConditionalUpdate.Updated
        assertNull(unset.record["rank"])
    }

    /** Concurrent "take one if any are left" updates never take more than there is, and roll back with a transaction. */
    @Test
    public fun conditionalUpdatesNeverOvershoot(): Unit = runTest {
        val source = createDataSource()
        if (source !is ConditionalWrites) return@runTest
        source.create(entries, mapOf(Resource.ID to text("e1"), "rank" to int(10)))
        val anyLeft = Filter.Compare("rank", Comparison.GTE, int(1))
        val taken = List(20) {
            async { source.transaction { tx -> (tx as ConditionalWrites).updateIf(entries, "e1", anyLeft, increments = mapOf("rank" to int(-1))) } }
        }.awaitAll()
        assertEquals(10, taken.count { it is ConditionalUpdate.Updated })
        assertEquals(10, taken.count { it == ConditionalUpdate.ConditionFailed })
        assertEquals(int(0), source.get(entries, "e1")!!["rank"])

        source.updateIf(entries, "e1", null, mapOf("rank" to int(1)))
        assertFailsWith<IllegalStateException> {
            source.transaction { tx ->
                (tx as ConditionalWrites).updateIf(entries, "e1", anyLeft, increments = mapOf("rank" to int(-1)))
                error("rolls back")
            }
        }
        assertEquals(int(1), source.get(entries, "e1")!!["rank"])
    }

    private suspend fun DataSource.seedForAggregates() {
        val rows = listOf(
            listOf("e1", "a", 3L, 10L, "1.5", "2026-01-01T00:00:00Z", "kiwi"),
            listOf("e2", "a", 4L, null, "2.25", "2026-03-01T00:00:00Z", "apple"),
            listOf("e3", "b", 5L, 30L, null, "2026-02-01T00:00:00Z", "fig"),
            listOf("e4", null, 1L, 1L, "0.1", null, null),
            listOf("e5", "a", null, 5L, "0.1", "2026-02-15T00:00:00Z", "date"),
        )
        for ((id, kind, rank, size, amount, at, title) in rows.map { it + List(7 - it.size) { null } }) {
            create(
                entries,
                mapOf(
                    Resource.ID to text(id as String),
                    "kind" to (kind as String?)?.let(::text),
                    "rank" to (rank as Long?)?.let(::int),
                    "size" to (size as Long?)?.let(::int),
                    "amount" to (amount as String?)?.let { Value.Decimal.of(it) },
                    "at" to (at as String?)?.let { Value.Timestamp(Instant.parse(it)) },
                    "title" to (title as String?)?.let(::text),
                ),
            )
        }
    }

    private operator fun <T> List<T>.component6(): T = this[5]
    private operator fun <T> List<T>.component7(): T = this[6]

    /** A source that offers [Aggregating] groups records and computes each function as the contract says. */
    @Test
    public fun aggregatesGroupAndCompute(): Unit = runTest {
        val source = createDataSource()
        if (source !is Aggregating) return@runTest
        source.seedForAggregates()
        val page = source.aggregate(
            entries,
            AggregateQuery(
                groupBy = listOf("kind"),
                measures = listOf(
                    Measure(AggregateFunction.COUNT),
                    Measure(AggregateFunction.COUNT, "size"),
                    Measure(AggregateFunction.SUM, "rank"),
                    Measure(AggregateFunction.SUM, "size"),
                    Measure(AggregateFunction.SUM, "amount"),
                    Measure(AggregateFunction.AVG, "rank"),
                    Measure(AggregateFunction.MIN, "at"),
                    Measure(AggregateFunction.MAX, "title"),
                ),
            ),
        )
        assertFalse(page.hasMore)
        // Groups come by kind, and the records without one last.
        assertEquals(listOf(text("a"), text("b"), null), page.groups.map { it.key["kind"] })
        val a = page.groups[0].values
        assertEquals(int(3), a["count"])
        assertEquals(int(2), a["count_size"])
        assertEquals(int(7), a["sum_rank"])
        assertEquals(int(15), a["sum_size"])
        assertEquals(Value.Decimal.of("3.85"), a["sum_amount"])
        assertEquals(Value.Decimal.of("3.5"), a["avg_rank"])
        assertEquals(Value.Timestamp(Instant.parse("2026-01-01T00:00:00Z")), a["min_at"])
        assertEquals(text("kiwi"), a["max_title"])
        val b = page.groups[1].values
        assertNull(b["sum_amount"])
        assertEquals(Value.Decimal.of("5"), b["avg_rank"])
        assertEquals(int(1), page.groups[2].values["count"])
    }

    /** An instant grouped by a time bucket counts in the bucket that holds it, in UTC, and sorts by the bucket. */
    @Test
    public fun aggregatesGroupByTimeBuckets(): Unit = runTest {
        val source = createDataSource()
        if (source !is Aggregating) return@runTest
        val times = listOf("2026-02-15T23:30:00Z", "2026-02-16T00:15:00Z", "2026-02-16T22:00:00Z", "2026-12-31T23:59:59Z", "2027-01-01T00:00:00Z", null)
        times.forEachIndexed { i, at ->
            source.create(entries, mapOf(Resource.ID to text("t$i"), "size" to int(i.toLong()), "at" to at?.let { Value.Timestamp(Instant.parse(it)) }))
        }
        fun stamp(at: String) = Value.Timestamp(Instant.parse(at))
        suspend fun buckets(bucket: TimeBucket, sort: List<Sort> = emptyList(), limit: Int = 1000): List<Pair<Value?, Value?>> =
            source.aggregate(entries, AggregateQuery(listOf("at"), listOf(Measure(AggregateFunction.COUNT)), sort = sort, limit = limit, buckets = mapOf("at" to bucket)))
                .groups.map { it.key["at"] to it.values["count"] }

        assertEquals(
            listOf(stamp("2026-02-15T00:00:00Z") to int(1), stamp("2026-02-16T00:00:00Z") to int(2), stamp("2026-12-31T00:00:00Z") to int(1), stamp("2027-01-01T00:00:00Z") to int(1), null to int(1)),
            buckets(TimeBucket.DAY),
        )
        // 2026-02-15 is a Sunday, and 2027-01-01 a Friday in the week from Monday 2026-12-28.
        assertEquals(
            listOf(stamp("2026-02-09T00:00:00Z") to int(1), stamp("2026-02-16T00:00:00Z") to int(2), stamp("2026-12-28T00:00:00Z") to int(2), null to int(1)),
            buckets(TimeBucket.WEEK),
        )
        assertEquals(listOf(stamp("2026-02-01T00:00:00Z") to int(3), stamp("2026-12-01T00:00:00Z") to int(1), stamp("2027-01-01T00:00:00Z") to int(1), null to int(1)), buckets(TimeBucket.MONTH))
        assertEquals(listOf(stamp("2026-01-01T00:00:00Z") to int(4), stamp("2027-01-01T00:00:00Z") to int(1), null to int(1)), buckets(TimeBucket.YEAR))
        // The latest months first, as a time series' last points.
        val latest = source.aggregate(entries, AggregateQuery(listOf("at"), listOf(Measure(AggregateFunction.SUM, "size")), Filter.IsNull("at", false), listOf(Sort("at", Direction.DESC)), 2, mapOf("at" to TimeBucket.MONTH)))
        assertEquals(listOf(stamp("2027-01-01T00:00:00Z") to int(4), stamp("2026-12-01T00:00:00Z") to int(3)), latest.groups.map { it.key["at"] to it.values["sum_size"] })
        assertTrue(latest.hasMore)

        for (bad in listOf(mapOf("kind" to TimeBucket.DAY), mapOf("rank" to TimeBucket.DAY))) {
            val query = AggregateQuery(listOf("at", "rank"), listOf(Measure(AggregateFunction.COUNT)), buckets = bad)
            assertFailsWith<InvalidQueryException>("$bad") { source.aggregate(entries, query) }
        }
    }

    /** No group fields make one group, even of no records; an average is exact; a filter narrows what counts. */
    @Test
    public fun aggregatesOverEverythingOrNothing(): Unit = runTest {
        val source = createDataSource()
        if (source !is Aggregating) return@runTest
        val measures = listOf(Measure(AggregateFunction.COUNT), Measure(AggregateFunction.SUM, "size"), Measure(AggregateFunction.AVG, "size"))
        val empty = source.aggregate(entries, AggregateQuery(measures = measures)).groups.single().values
        assertEquals(int(0), empty["count"])
        assertNull(empty["sum_size"])
        assertNull(empty["avg_size"])

        source.seedForAggregates()
        val all = source.aggregate(entries, AggregateQuery(measures = measures)).groups.single()
        assertEquals(emptyMap(), all.key)
        assertEquals(int(46), all.values["sum_size"])
        assertEquals(Value.Decimal.of("11.5"), all.values["avg_size"])
        val thirds = source.aggregate(entries, AggregateQuery(measures = listOf(Measure(AggregateFunction.AVG, "rank")), filter = Filter.Compare("rank", Comparison.GTE, int(3))))
        assertEquals(Value.Decimal.of("4"), thirds.groups.single().values["avg_rank"])
        val third = source.aggregate(entries, AggregateQuery(measures = listOf(Measure(AggregateFunction.AVG, "amount")), filter = Filter.Compare("kind", Comparison.EQ, text("a"))))
        assertEquals(Value.Decimal.of("1.283333333333"), third.groups.single().values["avg_amount"])
    }

    /** Groups sort by a measure, with a limit that says whether more groups exist; bad queries are refused. */
    @Test
    public fun aggregatesSortLimitAndCheck(): Unit = runTest {
        val source = createDataSource()
        if (source !is Aggregating) return@runTest
        source.seedForAggregates()
        val bySize = source.aggregate(
            entries,
            AggregateQuery(groupBy = listOf("kind", "rank"), measures = listOf(Measure(AggregateFunction.SUM, "size", "total")), sort = listOf(Sort("total", Direction.DESC)), limit = 2),
        )
        assertTrue(bySize.hasMore)
        // Descending puts the group with no total first, then the largest.
        assertEquals(listOf(null, int(30)), bySize.groups.map { it.values["total"] })
        assertEquals(mapOf("kind" to text("a"), "rank" to int(4)), bySize.groups.first().key)
        val byAverage = source.aggregate(
            entries,
            AggregateQuery(groupBy = listOf("kind"), measures = listOf(Measure(AggregateFunction.AVG, "rank")), sort = listOf(Sort("avg_rank", Direction.ASC)), limit = 1),
        )
        assertEquals(null, byAverage.groups.single().key["kind"])
        assertTrue(byAverage.hasMore)

        for (bad in listOf(
            AggregateQuery(measures = listOf(Measure(AggregateFunction.SUM, "title"))),
            AggregateQuery(measures = listOf(Measure(AggregateFunction.MIN, "flag"))),
            AggregateQuery(measures = listOf(Measure(AggregateFunction.SUM))),
            AggregateQuery(measures = listOf(Measure(AggregateFunction.COUNT, "colour"))),
            AggregateQuery(measures = listOf(Measure(AggregateFunction.COUNT), Measure(AggregateFunction.COUNT))),
            AggregateQuery(measures = listOf(Measure(AggregateFunction.COUNT)), sort = listOf(Sort("rank"))),
        )) {
            assertFailsWith<InvalidQueryException>("$bad") { source.aggregate(entries, bad) }
        }
    }

    /** A typed model of `entries` is the same resource, and its typed queries return what the dynamic ones do. */
    @Test
    public fun typedQueriesMatchDynamicOnes(): Unit = runTest {
        assertEquals(entries, typedEntries.resource)
        val source = createDataSource()
        val repository = Repository(typedEntries, source)
        val kinds = listOf(Kind.A, Kind.B, Kind.C, null)
        for (n in 0 until 12) {
            repository.create(
                Entry(
                    id = "t%02d".format(n),
                    title = if (n % 3 == 0) "Typed $n" else "other $n",
                    rank = (n % 5).takeIf { it != 4 },
                    amount = Value.Decimal.of("${n % 4}.5"),
                    flag = n % 2 == 0,
                    kind = kinds[n % 4],
                ),
            )
        }
        suspend fun same(dynamic: Query, typed: io.github.basekt.data.TypedQuery<Entry>.() -> Unit) {
            val expected = source.list(entries, dynamic).items.ids()
            assertEquals(dynamic, repository.query(typed))
            assertEquals(expected, repository.list(typed).items.map { it.id })
        }
        same(
            Query(
                Filter.And(listOf(Filter.Compare("rank", Comparison.GTE, int(2)), Filter.Compare("flag", Comparison.EQ, Value.Bool(true)))),
                listOf(Sort("amount", Direction.DESC)),
            ),
        ) {
            // Renaming Entry.rank breaks this line at compile time, where the dynamic "rank" would only fail at runtime.
            // A value of the wrong type doesn't compile either: (Entry::rank eq "2") has no overload.
            where { (Entry::rank gte 2) and (Entry::flag eq true) }
            orderBy(Entry::amount, Direction.DESC)
        }
        same(Query(Filter.Or(listOf(Filter.In("kind", listOf(text("a"), text("c"))), Filter.IsNull("kind"))), limit = 5)) {
            where { (Entry::kind isIn listOf(Kind.A, Kind.C)) or Entry::kind.isNull() }
            limit = 5
        }
        same(Query(Filter.Not(Filter.Contains("title", "typed")), listOf(Sort("rank"), Sort("title", Direction.DESC)))) {
            where { not(Entry::title contains "typed") }
            orderBy(Entry::rank)
            orderBy(Entry::title, Direction.DESC)
        }
    }

    /** A repository creates, reads, updates and deletes typed values. */
    @Test
    public fun repositoriesRoundTripTypedValues(): Unit = runTest {
        val repository = Repository(typedEntries, createDataSource())
        val created = repository.create(Entry(title = "first", size = Long.MAX_VALUE, at = Instant.parse("2026-03-04T05:06:07.123Z"), kind = Kind.B))
        val id = assertNotNull(created.id)
        assertEquals(created, repository.get(id))
        val updated = repository.update(id, created.copy(title = null, rank = 7))
        assertEquals(created.copy(title = null, rank = 7), updated)
        assertEquals(mapOf(id to updated), repository.getMany(listOf(id, "missing")))
        assertNull(repository.update("missing", created))
        assertTrue(repository.delete(id))
        assertNull(repository.get(id))
    }

    /** Concurrent creates all land, with distinct ids. */
    @Test
    public fun concurrentCreatesAllLand(): Unit = runTest {
        val source = createDataSource()
        val created = (1..100).map { n -> async { source.create(entries, mapOf("rank" to int(n.toLong()))) } }.awaitAll()
        assertEquals(100, created.ids().toSet().size)
        assertEquals(100, source.list(entries, Query(limit = 1000)).items.size)
    }

    /** An entry, as a typed model of the contract's `entries` resource. */
    @Serializable
    public data class Entry(
        /** The id, generated when null. */
        val id: String? = null,
        /** A title. */
        val title: String? = null,
        /** A rank. */
        val rank: Int? = null,
        /** A size. */
        val size: Long? = null,
        /** An amount. */
        val amount: Value.Decimal? = null,
        /** A flag. */
        val flag: Boolean? = null,
        /** A moment. */
        val at: Instant? = null,
        /** A kind. */
        val kind: Kind? = null,
        /** The group's id. */
        val groupId: String? = null,
        /** An attached file's key. */
        val attachment: String? = null,
    )

    /** The kinds of an [Entry]. */
    @Serializable
    public enum class Kind {
        /** Kind a. */
        @SerialName("a") A,

        /** Kind b. */
        @SerialName("b") B,

        /** Kind c. */
        @SerialName("c") C,
    }

    /** The schema the contract runs on: every field type and both relation kinds. */
    public companion object {
        /** The `entries` resource of [contractSchema], declared as a typed model of [Entry]. */
        public val typedEntries: Model<Entry> = model<Entry>("entries") {
            belongsTo(Entry::groupId, "groups")
            file(Entry::attachment, "docs")
        }

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
                file("attachment", "docs")
            }
        }
    }
}
