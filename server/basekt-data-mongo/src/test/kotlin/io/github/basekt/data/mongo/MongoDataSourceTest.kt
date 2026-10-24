package io.github.basekt.data.mongo

import com.mongodb.kotlin.client.coroutine.MongoCollection
import io.github.basekt.data.Comparison
import io.github.basekt.data.DuplicateIdException
import io.github.basekt.data.Filter
import io.github.basekt.data.Query
import io.github.basekt.data.Sequencing
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
import io.github.basekt.schema.schema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.bson.BsonBoolean
import org.bson.BsonDateTime
import org.bson.BsonDocument
import org.bson.BsonDouble
import org.bson.BsonInt32
import org.bson.BsonNull
import org.bson.BsonObjectId
import org.bson.BsonString
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** What the MongoDB adapter does that the contract can't ask of every adapter. */
class MongoDataSourceTest {
    /** A made-up question bank, laid out like documents another program wrote: a singular collection, ObjectId ids. */
    private val bank = schema {
        resource("questions") {
            reference("subjectId", "subjects") { required() }
            string("text")
            int("points")
            decimal("weight")
            bool("isActive")
            instant("createdAt")
            string("code") { unique() }
        }
        resource("subjects") {
            string("name")
        }
    }
    private val questions = bank.resource("questions")!!
    private val mapping = MongoMapping(
        collections = mapOf("questions" to "question"),
        objectIds = mapOf("questions" to setOf(Resource.ID, "subjectId"), "subjects" to setOf(Resource.ID)),
        indexes = listOf(IndexSpec("questions", "isActive", "createdAt")),
    )

    private val databases = mutableListOf<String>()

    private fun database(): String {
        assumeTrue(mongoAvailable(), "No MongoDB: pass -Pbasekt.mongo.url, start Docker, or pass -Pbasekt.mongo.embedded=true.")
        return freshDatabaseName().also { databases += it }
    }

    private fun source(name: String = database(), mapping: MongoMapping = this.mapping) = MongoDataSource(mongoClient, name, bank, mapping)

    private fun raw(name: String, collection: String = "question"): MongoCollection<BsonDocument> =
        mongoClient.getDatabase(name).getCollection<BsonDocument>(collection)

    @AfterEach
    fun dropDatabases(): Unit = runBlocking {
        databases.forEach { mongoClient.getDatabase(it).drop() }
        databases.clear()
    }

    private fun text(value: String) = Value.Text(value)

    @Test
    fun `ids and references named in the mapping are ObjectIds in the documents, and hex strings in records`(): Unit = runBlocking {
        val name = database()
        val source = source(name)
        val id = ObjectId().toHexString()
        val subject = ObjectId().toHexString()
        val created = source.create(questions, mapOf(Resource.ID to text(id), "subjectId" to text(subject), "text" to text("What?")))
        assertEquals(text(id), created[Resource.ID])
        assertEquals(text(subject), created["subjectId"])

        val stored = raw(name).find().toList().single()
        assertIs<BsonObjectId>(stored["_id"])
        assertIs<BsonObjectId>(stored["subjectId"])
        assertIs<BsonString>(stored["text"])
        assertEquals(id, stored.getObjectId("_id").value.toHexString())

        // A query by hex string finds them, and an id that is not hex is stored and found as a string.
        assertEquals(listOf(id), source.list(questions, Query(Filter.Compare("subjectId", Comparison.EQ, text(subject)))).items.map { (it[Resource.ID] as Value.Text).value })
        assertNotNull(source.get(questions, id))
        source.create(questions, mapOf(Resource.ID to text("not-hex"), "subjectId" to text("also not hex")))
        assertIs<BsonString>(raw(name).find(BsonDocument("_id", BsonString("not-hex"))).toList().single()["subjectId"])
        assertFailsWith<DuplicateIdException> { source.create(questions, mapOf(Resource.ID to text(id))) }
    }

    @Test
    fun `new ids are ObjectId hex strings`(): Unit = runBlocking {
        val created = source().create(questions, mapOf("subjectId" to text(ObjectId().toHexString())))
        assertTrue(ObjectId.isValid((created[Resource.ID] as Value.Text).value))
    }

    @Test
    fun `documents another program wrote are read as they are, and what the schema doesn't name is left alone`(): Unit = runBlocking {
        val name = database()
        val source = source(name)
        val id = ObjectId()
        val subject = ObjectId()
        // No `text`, `weight` or `code`; `points` an int32 and then a double; a date; an extra field of the other program's.
        raw(name).insertOne(
            BsonDocument("_id", BsonObjectId(id))
                .append("subjectId", BsonObjectId(subject)).append("points", BsonInt32(3)).append("isActive", BsonBoolean(true))
                .append("createdAt", BsonDateTime(1_770_000_000_123)).append("legacy", BsonString("keep me")),
        )
        raw(name).insertOne(BsonDocument("_id", BsonObjectId(ObjectId())).append("subjectId", BsonObjectId(subject)).append("points", BsonDouble(4.0)).append("weight", BsonDouble(1.5)))

        val read = source.get(questions, id.toHexString())!!
        assertEquals(Value.Integer(3), read["points"])
        assertNull(read["text"])
        assertNull(read["weight"])
        assertEquals(Value.Timestamp(Instant.fromEpochMilliseconds(1_770_000_000_123)), read["createdAt"])
        assertEquals(Value.Bool(true), read["isActive"])
        assertEquals(listOf(Value.Integer(4)), source.list(questions, Query(Filter.Compare("points", Comparison.GT, Value.Integer(3)))).items.map { it["points"] })
        assertEquals(Value.Decimal.of("1.5"), source.list(questions, Query(Filter.Compare("points", Comparison.GT, Value.Integer(3)))).items.single()["weight"])

        // A missing field counts as null.
        assertEquals(2, source.count(questions, Filter.IsNull("text")))
        assertEquals(1, source.count(questions, Filter.IsNull("createdAt", isNull = false)))

        source.update(questions, id.toHexString(), mapOf("text" to text("Now with text")))
        val after = raw(name).find(BsonDocument("_id", BsonObjectId(id))).toList().single()
        assertEquals("keep me", after.getString("legacy").value)
        assertEquals("Now with text", after.getString("text").value)
        assertEquals(3, after.getInt32("points").value)
    }

    @Test
    fun `a null is stored as a null, and an update to null clears the field`(): Unit = runBlocking {
        val name = database()
        val source = source(name)
        val id = ObjectId().toHexString()
        source.create(questions, mapOf(Resource.ID to text(id), "text" to text("t")))
        assertIs<BsonNull>(raw(name).find().toList().single()["points"])
        source.update(questions, id, mapOf("text" to null))
        assertIs<BsonNull>(raw(name).find().toList().single()["text"])
    }

    @Test
    fun `instants keep their milliseconds and drop what is finer`(): Unit = runBlocking {
        val source = source()
        val created = source.create(questions, mapOf("createdAt" to Value.Timestamp(Instant.parse("2026-03-04T05:06:07.123456789Z"))))
        assertEquals(Value.Timestamp(Instant.parse("2026-03-04T05:06:07.123Z")), created["createdAt"])
    }

    @Test
    fun `a collection prefix and a name for one resource are honoured`(): Unit = runBlocking {
        val name = database()
        val source = source(name, MongoMapping(collectionPrefix = "app_", collections = mapOf("questions" to "question")))
        source.create(questions, mapOf("text" to text("a")))
        source.create(bank.resource("subjects")!!, mapOf("name" to text("s")))
        assertEquals(1, raw(name, "question").countDocuments())
        assertEquals(1, raw(name, "app_subjects").countDocuments())
    }

    @Test
    fun `migrate creates the indexes once, and a unique field may be left null by many records`(): Unit = runBlocking {
        val name = database()
        val source = source(name)
        val first = source.migrate()
        assertTrue(first.applied.any { "basekt_unique_code" in it }, first.applied.toString())
        assertTrue(first.applied.any { "basekt_subjectId_id" in it })
        assertTrue(first.applied.any { "basekt_isActive_createdAt_id" in it })
        assertEquals(emptyList(), first.notes)
        assertEquals(emptyList(), source.migrate().applied, "a second run finds nothing to do")

        source.create(questions, mapOf("text" to text("no code 1")))
        source.create(questions, mapOf("text" to text("no code 2")))
        source.create(questions, mapOf("code" to text("A-1")))
        assertFailsWith<com.mongodb.MongoWriteException> { source.create(questions, mapOf("code" to text("A-1"))) }
    }

    @Test
    fun `an index that exists under another name is left, and said so`(): Unit = runBlocking {
        val name = database()
        raw(name).createIndex(BsonDocument("subjectId", BsonInt32(1)).append("_id", BsonInt32(1)), com.mongodb.client.model.IndexOptions().name("theirs"))
        val report = source(name).migrate()
        assertTrue(report.notes.any { "already has an index" in it }, report.notes.toString())
    }

    @Test
    fun `a failed nested transaction takes back its updates, deletes and counter numbers, and the outer goes on`(): Unit = runBlocking {
        val source = source()
        val kept = source.create(questions, mapOf(Resource.ID to text(ObjectId().toHexString()), "text" to text("before"), "points" to Value.Integer(1)))
        val gone = source.create(questions, mapOf(Resource.ID to text(ObjectId().toHexString()), "text" to text("to delete")))
        val keptId = (kept[Resource.ID] as Value.Text).value
        val goneId = (gone[Resource.ID] as Value.Text).value
        assertEquals(1L, source.nextValue("bank.n"))

        source.transaction { outer ->
            outer.update(questions, keptId, mapOf("text" to text("outer wrote")))
            runCatching {
                outer.transaction { inner ->
                    inner.update(questions, keptId, mapOf("text" to text("inner wrote"), "points" to Value.Integer(99)))
                    inner.delete(questions, goneId)
                    inner.create(questions, mapOf(Resource.ID to text(ObjectId().toHexString()), "text" to text("inner created")))
                    assertEquals(2L, (inner as Sequencing).nextValue("bank.n"))
                    error("inner fails")
                }
            }
            assertEquals(2L, (outer as Sequencing).nextValue("bank.n"), "the number the inner took is given back")
        }

        val after = source.get(questions, keptId)!!
        assertEquals(text("outer wrote"), after["text"], "the outer's own write stays, the inner's is gone")
        assertEquals(Value.Integer(1), after["points"])
        assertNotNull(source.get(questions, goneId), "the inner's delete is undone")
        assertEquals(2, source.count(questions), "the inner's create is undone")
        assertEquals(3L, source.nextValue("bank.n"))
    }

    @Test
    fun `an inner transaction that succeeds is undone with the outer one that then fails`(): Unit = runBlocking {
        val source = source()
        val id = ObjectId().toHexString()
        assertFailsWith<IllegalStateException> {
            source.transaction { outer ->
                outer.transaction { inner -> inner.create(questions, mapOf(Resource.ID to text(id), "text" to text("inner"))) }
                error("outer fails")
            }
        }
        assertNull(source.get(questions, id))
    }

    @Test
    fun `the block of a transaction that hit a write conflict runs again`(): Unit = runBlocking {
        val source = source()
        val id = ObjectId().toHexString()
        source.create(questions, mapOf(Resource.ID to text(id), "points" to Value.Integer(0)))
        val runs = java.util.concurrent.atomic.AtomicInteger()
        // Two transactions that read and then write the same record: one of them is told to try again.
        val results = (1..8).map {
            async(Dispatchers.IO) {
                source.transaction { tx ->
                    runs.incrementAndGet()
                    val points = (tx.get(questions, id)!!["points"] as Value.Integer).value
                    tx.update(questions, id, mapOf("points" to Value.Integer(points + 1)))
                }
            }
        }.map { it.await() }
        assertEquals(8, results.size)
        assertEquals(Value.Integer(8), source.get(questions, id)!!["points"], "no increment was lost")
        assertTrue(runs.get() >= 8, "the blocks ran ${runs.get()} times in all")
    }
}
