package io.github.basekt.data.mongo

import com.mongodb.ErrorCategory
import com.mongodb.MongoCommandException
import com.mongodb.MongoException
import com.mongodb.MongoWriteException
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.kotlin.client.coroutine.ClientSession
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoCollection
import io.github.basekt.data.Aggregating
import io.github.basekt.data.AggregatePage
import io.github.basekt.data.AggregateQuery
import io.github.basekt.data.ConditionalUpdate
import io.github.basekt.data.ConditionalWrites
import io.github.basekt.data.Counting
import io.github.basekt.data.CursorCodec
import io.github.basekt.data.DataSource
import io.github.basekt.data.DuplicateIdException
import io.github.basekt.data.Filter
import io.github.basekt.data.Page
import io.github.basekt.data.Query
import io.github.basekt.data.Sequencing
import io.github.basekt.data.checkAggregate
import io.github.basekt.data.checkConditionalUpdate
import io.github.basekt.data.checkQuery
import io.github.basekt.schema.FieldType
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.bson.BsonArray
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonInt64
import org.bson.BsonNull
import org.bson.BsonString
import org.bson.BsonValue
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A [DataSource] over MongoDB, in [databaseName] of [client]: one collection per resource, laid out as [mapping] says.
 * It offers [Counting], [Sequencing], [ConditionalWrites] and [Aggregating], and passes the same contract suite as the other adapters.
 *
 * Needs MongoDB 5.0 or later (4.2 without time buckets in aggregates), and a replica set (a single node will do) for [transaction]s.
 *
 * - **Ids** are ObjectId hex strings by default ([newId]); name `id` in [MongoMapping.objectIds] to store them as
 *   ObjectIds. Records written with their own ids keep them.
 * - **Transactions** are MongoDB's. A transient failure, such as a write conflict with another transaction, runs the
 *   block again, for up to [transactionRetryTimeout], so the block must not do anything that can't be repeated.
 *   MongoDB has no savepoints, so a nested transaction keeps an undo log of its own writes and plays it back if it fails:
 *   the outer transaction goes on without them. A write that MongoDB itself rejects, such as a duplicate id, aborts the
 *   whole transaction, whatever the code does with the exception.
 * - **Sort order** puts nulls last when ascending and first when descending, as the other adapters do, so a sorted list
 *   is read through an aggregation and can't use an index for its order. A list without a sort is in id order and can.
 * - **Instants** are dates, which hold milliseconds.
 *
 * Call [migrate] at startup to create the indexes; nothing else here changes the database's structure.
 */
public class MongoDataSource(
    private val client: MongoClient,
    databaseName: String,
    schema: Schema,
    private val mapping: MongoMapping = MongoMapping(),
    private val newId: () -> String = { ObjectId().toHexString() },
    private val transactionRetryTimeout: Duration = 30.seconds,
) : DataSource, Counting, Sequencing, ConditionalWrites, Aggregating {
    private val database = client.getDatabase(databaseName)
    private val documents: Map<String, DocumentMapping>
    private val root: Session

    init {
        schema.requireValid()
        mapping.check(schema)
        require(transactionRetryTimeout >= Duration.ZERO) { "transactionRetryTimeout can't be negative, not $transactionRetryTimeout." }
        documents = schema.resources.associate { it.name to DocumentMapping(it, mapping) }
        root = Session(null, null)
    }

    /**
     * Creates the indexes the schema wants that don't exist yet, and reports what it did. Existing ones are never changed
     * or dropped, and documents are never touched. It creates, per resource:
     * - a unique index for each `unique()` field, over the documents where the field is set, so many records may leave
     *   it null;
     * - an index on `(reference, _id)` for each reference field, which serves relation lookups and keyset pages;
     * - an index on `(fields..., _id)` for each of [MongoMapping.indexes].
     *
     * An index that can't be created, such as a unique one while two documents share a value, or one whose keys exist under
     * another name, is listed in [MigrationReport.notes], and nothing is thrown.
     */
    public suspend fun migrate(): MigrationReport {
        val applied = mutableListOf<String>()
        val notes = mutableListOf<String>()
        for (resource in documents.values) {
            val collection = database.getCollection<BsonDocument>(resource.collection)
            val wanted = mutableListOf<Triple<String, BsonDocument, IndexOptions>>()
            for (field in resource.resource.fields.filter { it.unique }) {
                val types = BsonArray(listOf("string", "objectId", "int", "long", "decimal", "bool", "date").map { BsonString(it) })
                wanted += Triple(
                    "basekt_unique_${field.name}",
                    BsonDocument(field.name, BsonInt32(1)),
                    IndexOptions().unique(true).partialFilterExpression(BsonDocument(field.name, BsonDocument("\$type", types))),
                )
            }
            for (field in resource.resource.fields.filter { it.type == FieldType.REFERENCE }) {
                wanted += Triple("basekt_${field.name}_id", BsonDocument(field.name, BsonInt32(1)).append(ID_KEY, BsonInt32(1)), IndexOptions())
            }
            for (index in mapping.indexes.filter { it.resource == resource.resource.name }) {
                val keys = BsonDocument().also { doc -> index.fields.forEach { doc[resource.key(it)] = BsonInt32(1) } }
                if (ID_KEY !in keys) keys[ID_KEY] = BsonInt32(1)
                wanted += Triple("basekt_${index.fields.joinToString("_")}_id", keys, IndexOptions())
            }
            val existing = collection.listIndexes<BsonDocument>().toList().map { it.getString("name").value }.toSet()
            for ((name, keys, options) in wanted) {
                if (name in existing) continue
                try {
                    collection.createIndex(keys, options.name(name))
                    applied += "Created index $name on ${resource.collection}."
                } catch (e: MongoCommandException) {
                    notes += when (e.errorCode) {
                        85, 86 -> "${resource.collection} already has an index on ${keys.keys.joinToString()} under another name or with other options; it was left as it is."
                        else -> "Index $name on ${resource.collection} was not created: ${e.errorMessage}"
                    }
                }
            }
        }
        return MigrationReport(applied, notes)
    }

    override suspend fun get(resource: Resource, id: String): Record? = root.get(resource, id)

    override suspend fun getMany(resource: Resource, ids: Collection<String>): Map<String, Record> = root.getMany(resource, ids)

    override suspend fun list(resource: Resource, query: Query): Page = root.list(resource, query)

    override suspend fun count(resource: Resource, filter: Filter?): Long = root.count(resource, filter)

    override suspend fun create(resource: Resource, record: Record): Record = root.create(resource, record)

    override suspend fun update(resource: Resource, id: String, changes: Record): Record? = root.update(resource, id, changes)

    override suspend fun delete(resource: Resource, id: String): Boolean = root.delete(resource, id)

    override suspend fun nextValue(name: String, start: Long): Long = root.nextValue(name, start)

    override suspend fun aggregate(resource: Resource, query: AggregateQuery): AggregatePage = root.aggregate(resource, query)

    override suspend fun updateIf(
        resource: Resource,
        id: String,
        condition: Filter?,
        changes: Record,
        increments: Map<String, Value>,
    ): ConditionalUpdate = root.updateIf(resource, id, condition, changes, increments)

    override suspend fun <T> transaction(block: suspend (DataSource) -> T): T = root.transaction(block)

    private fun mappingOf(resource: Resource): DocumentMapping =
        documents[resource.name] ?: throw IllegalArgumentException("'${resource.name}' is not in this source's schema.")

    /** Runs [block] in a MongoDB transaction, running it again after a transient failure. */
    private suspend fun <T> inTransaction(block: suspend (DataSource) -> T): T {
        val started = TimeSource.Monotonic.markNow()
        var attempt = 0
        while (true) {
            attempt++
            val session = client.startSession()
            try {
                session.startTransaction()
                val result = block(Session(session, UndoLog()))
                commit(session)
                return result
            } catch (e: Throwable) {
                withContext(NonCancellable) { runCatching { if (session.hasActiveTransaction()) session.abortTransaction() } }
                if (e is MongoException && e.hasErrorLabel("TransientTransactionError") && started.elapsedNow() < transactionRetryTimeout) {
                    // A little random wait, longer the more often it fails, so competing transactions stop colliding.
                    withContext(NonCancellable) { delay(Random.nextLong(2, 10L + 5L * minOf(attempt, 20))) }
                    continue
                }
                throw e
            } finally {
                session.close()
            }
        }
    }

    private suspend fun commit(session: ClientSession) {
        while (true) {
            try {
                session.commitTransaction()
                return
            } catch (e: MongoException) {
                if (!e.hasErrorLabel("UnknownTransactionCommitResult")) throw e
            }
        }
    }

    /** What a nested transaction has written, so it can be taken back without MongoDB's help. */
    private class UndoLog {
        private val steps = ArrayDeque<suspend () -> Unit>()

        fun add(step: suspend () -> Unit) {
            steps.addLast(step)
        }

        /** Plays the steps back, the last first. A step that fails ends the rollback; the original error is the one to report. */
        suspend fun rollBack() {
            while (steps.isNotEmpty()) steps.removeLast()()
        }

        /** Takes over [child]'s steps, so the parent's own rollback covers them. */
        fun absorb(child: UndoLog) {
            steps.addAll(child.steps)
        }
    }

    /** The calls of one connection: outside a transaction when [session] is null, else inside it. */
    private inner class Session(private val session: ClientSession?, private val undo: UndoLog?) :
        DataSource, Counting, Sequencing, ConditionalWrites, Aggregating {

        private fun collection(mapping: DocumentMapping): MongoCollection<BsonDocument> =
            database.getCollection<BsonDocument>(mapping.collection)

        private fun idFilter(mapping: DocumentMapping, id: String): BsonDocument =
            BsonDocument(ID_KEY, mapping.encode(Resource.ID, Value.Text(id)))

        private suspend fun findOne(collection: MongoCollection<BsonDocument>, filter: Bson): BsonDocument? =
            (session?.let { collection.find(it, filter) } ?: collection.find(filter)).limit(1).firstOrNull()

        override suspend fun get(resource: Resource, id: String): Record? {
            val mapping = mappingOf(resource)
            return findOne(collection(mapping), idFilter(mapping, id))?.let(mapping::toRecord)
        }

        override suspend fun getMany(resource: Resource, ids: Collection<String>): Map<String, Record> {
            if (ids.isEmpty()) return emptyMap()
            val mapping = mappingOf(resource)
            val filter = BsonDocument(ID_KEY, BsonDocument("\$in", BsonArray(ids.distinct().map { mapping.encode(Resource.ID, Value.Text(it)) })))
            val found = collection(mapping).let { c -> session?.let { c.find(it, filter) } ?: c.find(filter) }.toList()
            return found.map(mapping::toRecord).associateBy { (it[Resource.ID] as Value.Text).value }
        }

        override suspend fun list(resource: Resource, query: Query): Page {
            resource.checkQuery(query)
            val mapping = mappingOf(resource)
            val codec = CursorCodec(resource, query.sort)
            val conditions = listOfNotNull(
                query.filter?.let { mapping.predicate(it) },
                query.cursor?.let { mapping.keysetAfter(query.sort, codec.decode(it)) },
            )
            val match = when (conditions.size) {
                0 -> BsonDocument()
                1 -> conditions.single()
                else -> BsonDocument("\$and", BsonArray(conditions))
            }
            val collection = collection(mapping)
            val found: List<BsonDocument> = if (query.sort.isEmpty()) {
                (session?.let { collection.find(it, match) } ?: collection.find(match))
                    .sort(BsonDocument(ID_KEY, BsonInt32(1))).limit(query.limit + 1).toList()
            } else {
                val (flags, order) = mapping.ordering(query.sort)
                val pipeline = listOf(
                    BsonDocument("\$match", match),
                    BsonDocument("\$addFields", flags),
                    BsonDocument("\$sort", order),
                    BsonDocument("\$limit", BsonInt32(query.limit + 1)),
                    BsonDocument("\$project", BsonDocument().also { project -> flags.keys.forEach { project[it] = BsonInt32(0) } }),
                )
                (session?.let { collection.aggregate<BsonDocument>(it, pipeline) } ?: collection.aggregate<BsonDocument>(pipeline)).toList()
            }
            val page = found.take(query.limit).map(mapping::toRecord)
            val next = if (found.size > query.limit) codec.encode(codec.keyOf(page.last())) else null
            return Page(page, next)
        }

        override suspend fun count(resource: Resource, filter: Filter?): Long {
            resource.checkQuery(Query(filter))
            val mapping = mappingOf(resource)
            val match = filter?.let { mapping.predicate(it) } ?: BsonDocument()
            val collection = collection(mapping)
            return session?.let { collection.countDocuments(it, match) } ?: collection.countDocuments(match)
        }

        override suspend fun aggregate(resource: Resource, query: AggregateQuery): AggregatePage {
            resource.checkAggregate(query)
            val mapping = mappingOf(resource)
            val plan = AggregatePlan(mapping, query)
            val collection = collection(mapping)
            val documents = (session?.let { collection.aggregate<BsonDocument>(it, plan.pipeline) } ?: collection.aggregate<BsonDocument>(plan.pipeline)).toList()
            return plan.page(documents)
        }

        override suspend fun create(resource: Resource, record: Record): Record {
            val mapping = mappingOf(resource)
            val id = (record[Resource.ID] as Value.Text?)?.value ?: newId()
            val document = mapping.toDocument(id, record)
            val collection = collection(mapping)
            try {
                session?.let { collection.insertOne(it, document) } ?: collection.insertOne(document)
            } catch (e: MongoWriteException) {
                if (e.error.category == ErrorCategory.DUPLICATE_KEY && ID_KEY in e.error.message) throw DuplicateIdException(resource.name, id)
                throw e
            }
            undo?.add { deleteDocument(collection, idFilter(mapping, id)) }
            return mapping.toRecord(document)
        }

        override suspend fun update(resource: Resource, id: String, changes: Record): Record? {
            val mapping = mappingOf(resource)
            val fields = resource.fields.filter { it.name in changes }
            if (fields.isEmpty()) return get(resource, id)
            val collection = collection(mapping)
            val filter = idFilter(mapping, id)
            val before = undo?.let { findOne(collection, filter) ?: return null }
            val set = BsonDocument().also { doc -> fields.forEach { doc[it.name] = mapping.encodeOrNull(it.name, changes[it.name]) } }
            val update = BsonDocument("\$set", set)
            val options = FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
            val after = (session?.let { collection.findOneAndUpdate(it, filter, update, options) } ?: collection.findOneAndUpdate(filter, update, options))
                ?: return null
            if (before != null) undo?.add { restore(collection, filter, before) }
            return mapping.toRecord(after)
        }

        override suspend fun delete(resource: Resource, id: String): Boolean {
            val mapping = mappingOf(resource)
            val collection = collection(mapping)
            val filter = idFilter(mapping, id)
            val deleted = session?.let { collection.findOneAndDelete(it, filter) } ?: collection.findOneAndDelete(filter)
            if (deleted != null) undo?.add { restore(collection, filter, deleted) }
            return deleted != null
        }

        private suspend fun deleteDocument(collection: MongoCollection<BsonDocument>, filter: Bson) {
            session?.let { collection.deleteOne(it, filter) } ?: collection.deleteOne(filter)
        }

        /** Puts [document] back, whether the write being undone changed it or removed it. */
        private suspend fun restore(collection: MongoCollection<BsonDocument>, filter: Bson, document: BsonDocument) {
            val options = ReplaceOptions().upsert(true)
            session?.let { collection.replaceOne(it, filter, document, options) } ?: collection.replaceOne(filter, document, options)
        }

        override suspend fun nextValue(name: String, start: Long): Long {
            val counters = database.getCollection<BsonDocument>(mapping.sequencesCollection)
            val filter = BsonDocument(ID_KEY, BsonString(name))
            // The first call sets the counter to `start`, every later one adds one, in one atomic step.
            val next = BsonDocument(
                "\$cond",
                BsonArray(
                    listOf(
                        BsonDocument("\$eq", BsonArray(listOf(BsonDocument("\$type", BsonString("\$value")), BsonString("missing")))),
                        BsonInt64(start),
                        BsonDocument("\$add", BsonArray(listOf(BsonString("\$value"), BsonInt64(1)))),
                    ),
                ),
            )
            val update = listOf(BsonDocument("\$set", BsonDocument("value", next)))
            val options = FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.BEFORE)
            val before = session?.let { counters.findOneAndUpdate(it, filter, update, options) } ?: counters.findOneAndUpdate(filter, update, options)
            val previous = before?.get("value")?.asNumber()?.longValue()
            undo?.add {
                if (previous == null) {
                    session?.let { counters.deleteOne(it, filter) } ?: counters.deleteOne(filter)
                } else {
                    val set = BsonDocument("\$set", BsonDocument("value", BsonInt64(previous)))
                    session?.let { counters.updateOne(it, filter, set) } ?: counters.updateOne(filter, set)
                }
            }
            return if (previous == null) start else previous + 1
        }

        override suspend fun updateIf(
            resource: Resource,
            id: String,
            condition: Filter?,
            changes: Record,
            increments: Map<String, Value>,
        ): ConditionalUpdate {
            resource.checkConditionalUpdate(condition, changes, increments)
            val mapping = mappingOf(resource)
            val collection = collection(mapping)
            val byId = idFilter(mapping, id)
            val matching = if (condition == null) byId else BsonDocument("\$and", BsonArray(listOf(byId, mapping.predicate(condition))))
            val before = undo?.let { findOne(collection, byId) }

            val set = BsonDocument()
            for (field in resource.fields) {
                if (field.name in changes) set[field.name] = BsonDocument("\$literal", mapping.encodeOrNull(field.name, changes[field.name]))
                increments[field.name]?.let { set[field.name] = increment(field.name, field.type, mapping.encode(field.name, it)) }
            }
            val updated: BsonDocument? = if (set.isEmpty()) {
                findOne(collection, matching)
            } else {
                val options = FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
                val update = listOf(BsonDocument("\$set", set))
                session?.let { collection.findOneAndUpdate(it, matching, update, options) } ?: collection.findOneAndUpdate(matching, update, options)
            }
            if (updated == null) {
                val exists = session?.let { collection.countDocuments(it, byId) } ?: collection.countDocuments(byId)
                return if (exists == 0L) ConditionalUpdate.NotFound else ConditionalUpdate.ConditionFailed
            }
            if (before != null && set.isNotEmpty()) undo?.add { restore(collection, byId, before) }
            return ConditionalUpdate.Updated(mapping.toRecord(updated))
        }

        /** The expression that adds [amount] to a number field and leaves a null or missing one as it is. */
        private fun increment(field: String, type: FieldType, amount: BsonValue): BsonValue {
            val numbers = BsonArray(listOf("int", "long", "decimal", "double").map { BsonString(it) })
            val sum = BsonDocument("\$add", BsonArray(listOf(BsonString("\$$field"), amount)))
            val typed = when (type) {
                FieldType.INT -> BsonDocument("\$toInt", sum)
                FieldType.LONG -> BsonDocument("\$toLong", sum)
                else -> BsonDocument("\$toDecimal", sum)
            }
            return BsonDocument(
                "\$cond",
                BsonArray(listOf(BsonDocument("\$in", BsonArray(listOf(BsonDocument("\$type", BsonString("\$$field")), numbers))), typed, BsonString("\$$field"))),
            )
        }

        override suspend fun <T> transaction(block: suspend (DataSource) -> T): T {
            if (session == null) return inTransaction(block)
            // MongoDB has no savepoints: the child's writes are kept in an undo log, played back if it fails.
            val child = Session(session, UndoLog())
            return try {
                block(child).also { undo?.absorb(child.undo!!) }
            } catch (e: Throwable) {
                runCatching { child.undo!!.rollBack() }
                throw e
            }
        }
    }
}
