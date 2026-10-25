package io.github.basekt.data.firestore

import com.google.cloud.Timestamp
import com.google.cloud.firestore.Firestore
import io.github.basekt.data.Comparison
import io.github.basekt.data.Counting
import io.github.basekt.data.Direction
import io.github.basekt.data.Filter
import io.github.basekt.data.Query
import io.github.basekt.data.Sort
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
import io.github.basekt.schema.schema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** What the Firestore adapter does that the contract can't ask of every adapter. */
class FirestoreDataSourceTest {
    /** A made-up survey log, laid out like documents another program wrote: a singular collection, references as documents. */
    private val survey = schema {
        resource("sightings") {
            reference("siteId", "sites")
            int("count")
            decimal("depth")
            instant("seenAt")
            string("note")
        }
        resource("sites") {
            string("name")
        }
    }
    private val sightings = survey.resource("sightings")!!
    private val surveyMapping = FirestoreMapping(collections = mapOf("sightings" to "sighting"))

    /** A made-up board of cards, each with a required position, and weights stored as doubles. */
    private val board = schema {
        resource("cards") {
            int("position") { required() }
            string("lane") { required() }
            string("label")
            decimal("weight")
        }
    }
    private val cards = board.resource("cards")!!

    private val clients = mutableListOf<Firestore>()

    private fun firestore(): Firestore {
        assumeTrue(emulatorAvailable(), NO_EMULATOR)
        return freshFirestore().also { clients += it }
    }

    @AfterEach
    fun closeClients() {
        clients.forEach { it.close() }
        clients.clear()
    }

    private fun text(value: String) = Value.Text(value)
    private fun int(value: Long) = Value.Integer(value)
    private fun ids(records: List<Map<String, Value?>>) = records.map { (it[Resource.ID] as Value.Text).value }

    @Test
    fun existingDocumentsAreReadAsTheyAreAndKeepWhatNoFieldNames(): Unit = runBlocking {
        val firestore = firestore()
        val source = FirestoreDataSource(firestore, survey, surveyMapping)
        val raw = firestore.collection("sighting").document("s1")
        // Another program's document: a reference as a document, a double where BaseKt has an int, no depth, an extra field.
        raw.set(mapOf("siteId" to firestore.collection("sites").document("north"), "count" to 3.0, "seenAt" to Timestamp.ofTimeSecondsAndNanos(1_700_000_000, 123_456_000), "legacy" to "kept")).get()

        val record = source.get(sightings, "s1")!!
        assertEquals(text("north"), record["siteId"])
        assertEquals(int(3), record["count"])
        assertNull(record["depth"])
        assertNull(record["note"])
        assertEquals(Value.Timestamp(Instant.fromEpochSeconds(1_700_000_000, 123_456_000)), record["seenAt"])

        source.update(sightings, "s1", mapOf("note" to text("seen twice")))
        val stored = raw.get().get()
        assertEquals("kept", stored.getString("legacy"))
        assertEquals("seen twice", stored.getString("note"))
        assertEquals(listOf("s1"), ids(source.list(sightings, Query(Filter.Contains("note", "TWICE"))).items))
    }

    @Test
    fun aStrictSourceAnswersOnlyWhatFirestoreCan(): Unit = runBlocking {
        val source = FirestoreDataSource(firestore(), board, FirestoreMapping(decimals = DecimalStorage.DOUBLE), maxScan = 0)
        for (n in 1..9L) {
            source.create(cards, mapOf(Resource.ID to text("c$n"), "position" to int(10 - n), "lane" to text(if (n % 2 == 0L) "left" else "right"), "weight" to Value.Decimal.of("$n.5")))
        }
        // A required field sorts in Firestore, and a filter on the lane or a range on the sort field goes with it.
        val left = Query(Filter.Compare("lane", Comparison.EQ, text("left")), listOf(Sort("position")), limit = 2)
        val first = source.list(cards, left)
        assertEquals(listOf("c8", "c6"), ids(first.items))
        assertEquals(listOf("c4", "c2"), ids(source.list(cards, left.copy(cursor = first.nextCursor)).items))
        val range = Query(Filter.Compare("position", Comparison.LT, int(4)), listOf(Sort("position", Direction.DESC)))
        assertEquals(listOf("c7", "c8", "c9"), ids(source.list(cards, range).items))
        // Decimals stored as doubles sort and compare by value.
        val heavy = Query(Filter.Compare("weight", Comparison.GTE, Value.Decimal.of("7")), listOf(Sort("weight", Direction.DESC)))
        assertEquals(listOf("c9", "c8", "c7"), ids(source.list(cards, heavy).items))
        val either = Filter.Or(listOf(Filter.Compare(Resource.ID, Comparison.EQ, text("c1")), Filter.In("position", listOf(int(7)))))
        assertEquals(listOf("c1", "c3"), ids(source.list(cards, Query(either)).items))
        assertEquals(4, source.count(cards, Filter.Compare("lane", Comparison.EQ, text("left"))))
        assertEquals(3, source.count(cards, Filter.Compare("position", Comparison.GT, int(6))))

        // Text search, and a sort on a field that may be unset, would have to read documents in memory.
        assertFailsWith<UnsupportedQueryException> { source.list(cards, Query(Filter.Contains("label", "x"))) }
        assertFailsWith<UnsupportedQueryException> { source.list(cards, Query(sort = listOf(Sort("label")))) }
        assertFailsWith<UnsupportedQueryException> { source.count(cards, Filter.IsNull("label")) }
    }

    @Test
    fun workInMemoryStopsAtTheScanLimit(): Unit = runBlocking {
        val source = FirestoreDataSource(firestore(), board, maxScan = 400)
        for (n in 1..700L) source.create(cards, mapOf(Resource.ID to text("c%03d".format(n)), "position" to int(n), "lane" to text("one"), "label" to text(if (n in 2L..3L) "needle" else "hay")))
        // Matches early in id order fill the page, and the one after it, before the limit.
        val page = source.list(cards, Query(Filter.Contains("label", "needle"), limit = 1))
        assertEquals(listOf("c002"), ids(page.items))
        assertTrue(page.hasNext)
        val error = assertFailsWith<UnsupportedQueryException> { source.list(cards, Query(Filter.Contains("label", "nothing"))) }
        assertTrue("400" in error.message!!, error.message)
    }

    @Test
    fun aTransactionSeesItsOwnWritesInListsAndCounts(): Unit = runBlocking {
        val source = FirestoreDataSource(firestore(), board)
        for (n in 1..4L) source.create(cards, mapOf(Resource.ID to text("c$n"), "position" to int(n * 10), "lane" to text("a")))
        val (listed, counted) = source.transaction { tx ->
            tx.create(cards, mapOf(Resource.ID to text("new"), "position" to int(15), "lane" to text("a")))
            tx.update(cards, "c3", mapOf("lane" to text("b")))
            tx.delete(cards, "c1")
            val lane = Query(Filter.Compare("lane", Comparison.EQ, text("a")), listOf(Sort("position")), limit = 2)
            val first = tx.list(cards, lane)
            val second = tx.list(cards, lane.copy(cursor = first.nextCursor))
            ids(first.items + second.items) to (tx as Counting).count(cards, Filter.Compare("lane", Comparison.EQ, text("a")))
        }
        // Firestore's own c2 and c4 merge with the new card, in order, across pages; c1 is gone and c3 moved lanes.
        assertEquals(listOf("new", "c2", "c4"), listed)
        assertEquals(3, counted)
        assertEquals(listOf("c2", "c3", "c4", "new"), ids(source.list(cards).items))
    }

    @Test
    fun aRerunTransactionWritesOnce(): Unit = runBlocking {
        val source = FirestoreDataSource(firestore(), board)
        source.create(cards, mapOf(Resource.ID to text("c"), "position" to int(0), "lane" to text("a")))
        val runs = AtomicInteger()
        withContext(Dispatchers.Default) {
            List(8) {
                async {
                    source.transaction { tx ->
                        runs.incrementAndGet()
                        val position = (tx.get(cards, "c")!!["position"] as Value.Integer).value
                        tx.update(cards, "c", mapOf("position" to int(position + 1)))
                    }
                }
            }.awaitAll()
        }
        // However many times Firestore made a block run, each transaction's write landed once.
        assertEquals(int(8), source.get(cards, "c")!!["position"])
        assertTrue(runs.get() >= 8)
    }

    @Test
    fun idsThatCantNameADocumentAreNeverFound(): Unit = runBlocking {
        val source = FirestoreDataSource(firestore(), survey, surveyMapping)
        assertNull(source.get(sightings, "a/b"))
        assertEquals(emptyMap(), source.getMany(sightings, listOf("a/b", "..")))
        assertEquals(false, source.delete(sightings, "__x__"))
        assertFailsWith<IllegalArgumentException> { source.create(sightings, mapOf(Resource.ID to text("a/b"))) }
    }

    @Test
    fun theMappingIsChecked() {
        assertFailsWith<IllegalArgumentException> { FirestoreDataSource(firestore(), survey, FirestoreMapping(collections = mapOf("missing" to "x"))) }
        assertFailsWith<IllegalArgumentException> { FirestoreDataSource(firestore(), survey, FirestoreMapping(collections = mapOf("sightings" to "sites"))) }
        assertFailsWith<IllegalArgumentException> { FirestoreDataSource(firestore(), survey, FirestoreMapping(collectionPrefix = "a/")) }
    }
}
