package io.github.basekt.data.mongo

import com.mongodb.kotlin.client.coroutine.MongoCollection
import io.github.basekt.data.Filter
import io.github.basekt.data.Query
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
import io.github.basekt.schema.schema
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.bson.BsonArray
import org.bson.BsonBoolean
import org.bson.BsonDocument
import org.bson.BsonObjectId
import org.bson.BsonString
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Lists and object lists on MongoDB: arrays and sub-documents, including ones another program wrote. */
class MongoListFieldsTest {
    /** A made-up quiz bank whose cards embed their choices and clues and list their writers, as a document store would. */
    private val quiz = schema {
        resource("cards") {
            string("prompt")
            objects("choices") {
                itemIds()
                string("text") { required() }
                bool("isCorrect")
            }
            string("clues") { list() }
            reference("writerIds", "writers") { list() }
        }
        resource("writers") {
            string("name")
        }
    }
    private val cards = quiz.resource("cards")!!
    private val mapping = MongoMapping(
        collections = mapOf("cards" to "card"),
        objectIds = mapOf("cards" to setOf(Resource.ID, "choices.id", "writerIds"), "writers" to setOf(Resource.ID)),
    )
    private val databases = mutableListOf<String>()

    private fun database(): String {
        assumeTrue(mongoAvailable(), "No MongoDB: pass -Pbasekt.mongo.url, start Docker, or pass -Pbasekt.mongo.embedded=true.")
        mongoClient
        return freshDatabaseName().also { databases += it }
    }

    private fun raw(name: String): MongoCollection<BsonDocument> = mongoClient.getDatabase(name).getCollection<BsonDocument>("card")

    @AfterEach
    fun dropDatabases(): Unit = runBlocking {
        databases.forEach { mongoClient.getDatabase(it).drop() }
        databases.clear()
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun startServer() {
            if (mongoAvailable()) mongoClient
        }
    }

    private fun text(value: String) = Value.Text(value)

    @Test
    fun `a document another program wrote, with embedded items and ObjectId lists, is read as it is`(): Unit = runBlocking {
        val name = database()
        val source = MongoDataSource(mongoClient, name, quiz, mapping)
        val card = ObjectId()
        val right = ObjectId()
        val wrong = ObjectId()
        val writer = ObjectId()
        raw(name).insertOne(
            BsonDocument("_id", BsonObjectId(card)).append("prompt", BsonString("Largest planet?"))
                .append(
                    "choices",
                    BsonArray(
                        listOf(
                            BsonDocument("_id", BsonObjectId(wrong)).append("text", BsonString("Mars")).append("isCorrect", BsonBoolean(false)),
                            BsonDocument("_id", BsonObjectId(right)).append("text", BsonString("Jupiter")).append("isCorrect", BsonBoolean(true)),
                        ),
                    ),
                )
                .append("clues", BsonArray(listOf(BsonString("It has a great red spot."))))
                .append("writerIds", BsonArray(listOf(BsonObjectId(writer))))
                .append("legacy", BsonString("keep me")),
        )

        val read = source.get(cards, card.toHexString())!!
        val choices = (read["choices"] as Value.Items).values.map { it as Value.Object }
        assertEquals(listOf(wrong.toHexString(), right.toHexString()), choices.map { (it.values[Resource.ID] as Value.Text).value })
        assertEquals(listOf(text("Mars"), text("Jupiter")), choices.map { it.values["text"] })
        assertEquals(Value.Items(listOf(text("It has a great red spot."))), read["clues"])
        assertEquals(Value.Items(listOf(text(writer.toHexString()))), read["writerIds"])

        // `has` with a hex string finds the ObjectId in the array.
        val found = source.list(cards, Query(filter = Filter.Has("writerIds", listOf(text(writer.toHexString())))))
        assertEquals(listOf(card.toHexString()), found.items.map { (it[Resource.ID] as Value.Text).value })

        // A rewritten list keeps its items' ObjectIds, and the field the schema doesn't name is left alone.
        source.update(cards, card.toHexString(), mapOf("choices" to Value.Items(choices.reversed())))
        val stored = raw(name).find().toList().single()
        val storedChoices = stored.getArray("choices").values.map { it as BsonDocument }
        assertEquals(listOf(right, wrong), storedChoices.map { (it["_id"] as BsonObjectId).value })
        assertIs<BsonObjectId>(stored.getArray("writerIds").values.single())
        assertEquals("keep me", stored.getString("legacy").value)
    }
}
