package io.github.basekt.data.firestore

import com.google.api.core.ApiFuture
import com.google.api.core.ApiFutureCallback
import com.google.api.core.ApiFutures
import com.google.api.core.SettableApiFuture
import com.google.api.gax.rpc.ApiException
import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreException
import com.google.cloud.firestore.QuerySnapshot
import com.google.cloud.firestore.Transaction
import com.google.cloud.firestore.TransactionOptions
import io.github.basekt.data.ConditionalUpdate
import io.github.basekt.data.ConditionalWrites
import io.github.basekt.data.Counting
import io.github.basekt.data.CursorCodec
import io.github.basekt.data.DataException
import io.github.basekt.data.DataSource
import io.github.basekt.data.DuplicateIdException
import io.github.basekt.data.Filter
import io.github.basekt.data.InvalidQueryException
import io.github.basekt.data.Page
import io.github.basekt.data.Query
import io.github.basekt.data.Sequencing
import io.github.basekt.data.checkConditionalUpdate
import io.github.basekt.data.checkQuery
import io.github.basekt.data.plus
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import com.google.cloud.firestore.Query as FirestoreQuery

/**
 * A [DataSource] over Cloud Firestore, in the database [firestore] opens: one top-level collection per resource, laid
 * out as [mapping] says, with document ids as record ids. It offers [Counting], [Sequencing] and [ConditionalWrites],
 * and passes the same contract suite as the other adapters. It doesn't offer `Aggregating`: Firestore can't group.
 *
 * - **Ids** are Firestore's own random ids when a record is created without one ([newId] to change that). An id that
 *   can't name a document (empty, with a slash, `.` or `..`, `__like this__`) is never found and can't be created.
 * - **Queries.** Firestore answers a filter's equalities, `in`s, `or`s of equalities, ranges and `!=` itself, and
 *   orders a list when every sort key is a field that every match has: one the filter compares or requires to be set,
 *   or one the schema makes required. Firestore leaves documents without the field out of a sorted query and puts
 *   nulls first, so only then is its order BaseKt's. The rest (`contains`, `not`, a test for null, a range on a field
 *   the list isn't sorted by, a sort on a field that may be unset, decimals stored as strings) is checked in memory over the
 *   documents Firestore returns, reading on until the page is full. Such a query may read at most [maxScan]
 *   documents; past that it throws [UnsupportedQueryException], as every such query does when [maxScan] is 0. A
 *   query Firestore refuses for want of a composite index throws [MissingIndexException], whose message holds
 *   Firestore's link to create it. The emulator needs no indexes.
 * - **Transactions** are Firestore's, which lock what they read. A transaction's writes are kept in memory until its
 *   block returns, so its reads see them and a failure leaves nothing behind; nested transactions are savepoints of
 *   that log. When Firestore aborts a transaction over a conflict with another one, the whole block runs again, for
 *   up to [transactionRetryTimeout], and then throws [TransactionConflictException]. So the block may run more than
 *   once: what it writes through the source it is given lands once, but anything else it does, such as a hook that
 *   calls another service, happens once per run. Work like that belongs after the transaction, as BaseKt's own
 *   extensions do it once a write has committed.
 * - **Writes** outside a transaction run in one of their own, except a create, which is Firestore's create, tried
 *   again like a transaction when a transaction holding the document's lock makes Firestore abort it.
 * - **Instants** are timestamps, which hold microseconds.
 *
 * @param firestore the client of the database, from `FirestoreOptions` or the Firebase Admin SDK.
 * @param newId generates ids for records created without one; `null` for Firestore's random ids.
 * @param maxScan the most documents a query worked in memory may read; 0 to refuse every such query.
 * @param transactionRetryTimeout how long a transaction that keeps conflicting is run again before it gives up.
 */
public class FirestoreDataSource(
    private val firestore: Firestore,
    schema: Schema,
    private val mapping: FirestoreMapping = FirestoreMapping(),
    private val newId: (() -> String)? = null,
    private val maxScan: Int = 10_000,
    private val transactionRetryTimeout: Duration = 30.seconds,
) : DataSource, Counting, Sequencing, ConditionalWrites {
    private val documents: Map<String, DocumentMapping>
    private val planners: Map<String, Planner>
    private val sequences = firestore.collection(mapping.sequencesCollection)
    private val root = Session(null, null)
    private val retrying = TransactionOptions.createReadWriteOptionsBuilder().setNumberOfAttempts(TRANSACTION_ATTEMPTS).build()

    init {
        schema.requireValid()
        mapping.check(schema)
        require(maxScan >= 0) { "maxScan can't be negative, not $maxScan." }
        require(transactionRetryTimeout >= Duration.ZERO) { "transactionRetryTimeout can't be negative, not $transactionRetryTimeout." }
        documents = schema.resources.associate { it.name to DocumentMapping(it, firestore.collection(mapping.collectionOf(it.name)), mapping.decimals) }
        planners = documents.mapValues { Planner(it.value) }
    }

    override suspend fun get(resource: Resource, id: String): Record? = root.get(resource, id)

    override suspend fun getMany(resource: Resource, ids: Collection<String>): Map<String, Record> = root.getMany(resource, ids)

    override suspend fun list(resource: Resource, query: Query): Page = root.list(resource, query)

    override suspend fun count(resource: Resource, filter: Filter?): Long = root.count(resource, filter)

    override suspend fun create(resource: Resource, record: Record): Record = root.create(resource, record)

    override suspend fun update(resource: Resource, id: String, changes: Record): Record? = root.update(resource, id, changes)

    override suspend fun delete(resource: Resource, id: String): Boolean = root.delete(resource, id)

    override suspend fun nextValue(name: String, start: Long): Long = root.nextValue(name, start)

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

    /**
     * Runs [block] in a Firestore transaction, then writes what it logged. Firestore's client runs it again after a
     * conflict, keeping the transaction's place in line for the locks it waits on; when its attempts run out, the
     * whole transaction starts over here, after a short random wait, until [transactionRetryTimeout].
     */
    private suspend fun <T> inTransaction(block: suspend (Session) -> T): T {
        val started = TimeSource.Monotonic.markNow()
        var attempts = 0
        var round = 0
        while (true) {
            round++
            var failure: Throwable? = null
            try {
                return coroutineScope {
                    firestore.runAsyncTransaction({ transaction ->
                        attempts++
                        failure = null
                        val result = SettableApiFuture.create<T>()
                        launch {
                            try {
                                val session = Session(transaction, WriteLog(null))
                                val value = block(session)
                                session.log!!.writeTo(transaction)
                                result.set(value)
                            } catch (e: Throwable) {
                                failure = e
                                result.setException(e)
                            }
                        }
                        result
                    }, retrying).await()
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (e.conflict() || failure?.conflict() == true) {
                    if (started.elapsedNow() < transactionRetryTimeout) {
                        // A little random wait, longer the more often it fails, so competing transactions stop colliding.
                        withContext(NonCancellable) { delay(Random.nextLong(2, 10L + 5L * minOf(round, 20))) }
                        continue
                    }
                    throw TransactionConflictException(attempts, e)
                }
                throw failure ?: e
            }
        }
    }

    /** The writes of a transaction, kept until it commits; a nested transaction keeps its own over its parent's. */
    private class WriteLog(private val parent: WriteLog?) {
        private val entries = LinkedHashMap<String, Entry>()

        /** A document's state before the transaction ([base]) and as it wrote it ([data]); `null` for no document. */
        class Entry(val ref: DocumentReference, val base: Data?, val data: Data?)

        fun find(path: String): Entry? = entries[path] ?: parent?.find(path)

        fun put(ref: DocumentReference, base: Data?, data: Data?) {
            entries[ref.path] = Entry(ref, find(ref.path)?.base ?: base, data)
        }

        private fun all(): Map<String, Entry> = (parent?.all() ?: emptyMap()) + entries

        /** The documents written in the collection at [path]. */
        fun inCollection(path: String): List<Entry> = all().values.filter { it.ref.parent.path == path }

        /** Takes over [child]'s writes, which its parent now keeps. */
        fun absorb(child: WriteLog) {
            entries.putAll(child.entries)
        }

        /** Hands every logged write to [transaction], which commits them. */
        fun writeTo(transaction: Transaction) {
            for (entry in all().values) {
                when {
                    entry.data == null -> if (entry.base != null) transaction.delete(entry.ref)
                    entry.base == null -> transaction.create(entry.ref, entry.data)
                    else -> transaction.set(entry.ref, entry.data)
                }
            }
        }
    }

    /** A document as a transaction sees it: [data] now, `null` if there is none, and [base] before the transaction. */
    private class Current(val data: Data?, val base: Data?)

    /** The calls of one connection: outside a transaction when [transaction] is null, else inside it, logging to [log]. */
    private inner class Session(private val transaction: Transaction?, val log: WriteLog?) :
        DataSource, Counting, Sequencing, ConditionalWrites {

        private suspend fun read(ref: DocumentReference): DocumentSnapshot = (transaction?.get(ref) ?: ref.get()).await()

        private suspend fun read(mapping: DocumentMapping, query: FirestoreQuery): QuerySnapshot =
            indexed(mapping) { (transaction?.get(query) ?: query.get()).await() }

        /** The document at [ref] as this session sees it: its own write if it made one, else Firestore's. */
        private suspend fun current(ref: DocumentReference): Current {
            log?.find(ref.path)?.let { return Current(it.data, it.base) }
            val snapshot = read(ref)
            val data = if (snapshot.exists()) snapshot.data.orEmpty() else null
            return Current(data, data)
        }

        override suspend fun get(resource: Resource, id: String): Record? {
            val mapping = mappingOf(resource)
            val ref = mapping.ref(id) ?: return null
            return current(ref).data?.let { mapping.toRecord(id, it) }
        }

        override suspend fun getMany(resource: Resource, ids: Collection<String>): Map<String, Record> {
            val mapping = mappingOf(resource)
            val found = LinkedHashMap<String, Record>()
            val unread = mutableListOf<DocumentReference>()
            for (id in ids.distinct()) {
                val ref = mapping.ref(id) ?: continue
                val logged = log?.find(ref.path)
                if (logged == null) unread += ref else logged.data?.let { found[id] = mapping.toRecord(id, it) }
            }
            if (unread.isNotEmpty()) {
                val snapshots = (transaction?.getAll(*unread.toTypedArray()) ?: firestore.getAll(*unread.toTypedArray())).await()
                for (snapshot in snapshots) if (snapshot.exists()) found[snapshot.id] = mapping.toRecord(snapshot.id, snapshot.data.orEmpty())
            }
            return found
        }

        override suspend fun list(resource: Resource, query: Query): Page {
            resource.checkQuery(query)
            val mapping = mappingOf(resource)
            val codec = CursorCodec(resource, query.sort)
            val after = query.cursor?.let(codec::decode)
            val plan = planners.getValue(resource.name).forList(query.filter, query.sort)
            if (plan.nothing) return Page(emptyList(), null)
            val order = keyOrder(query.sort)
            val wanted = query.limit + 1
            // This transaction's own writes replace what Firestore holds for those documents.
            val written = log?.inCollection(mapping.collection.path).orEmpty()
            val writtenPaths = written.mapTo(HashSet()) { it.ref.path }
            val own = written.mapNotNull { entry ->
                entry.data?.let { mapping.toRecord(entry.ref.id, it) }
                    ?.takeIf { record -> query.filter?.let { record.matches(it) } ?: true }
                    ?.takeIf { record -> after == null || order.compare(codec.keyOf(record), after) > 0 }
            }
            val stored = if (plan.order != null) {
                var firestoreQuery = plan.query(mapping)
                if (after != null) firestoreQuery = firestoreQuery.startAfter(*plan.cursorValues(mapping, query.sort, after))
                scan(resource, mapping, plan, firestoreQuery, writtenPaths, wanted)
            } else {
                scan(resource, mapping, plan, plan.query(mapping), writtenPaths, null)
                    .filter { after == null || order.compare(codec.keyOf(it), after) > 0 }
            }
            val records = (stored + own).sortedWith { a, b -> order.compare(codec.keyOf(a), codec.keyOf(b)) }.take(wanted)
            val page = records.take(query.limit)
            return Page(page, if (records.size > query.limit) codec.encode(codec.keyOf(page.last())) else null)
        }

        /**
         * Reads the records [query] returns that match the plan's residual, skipping the documents at [skipped], in
         * batches, until [wanted] are found, or all of them when it is `null`. Throws [UnsupportedQueryException] when
         * the work in memory would read more than [maxScan] documents.
         */
        private suspend fun scan(
            resource: Resource,
            mapping: DocumentMapping,
            plan: QueryPlan,
            query: FirestoreQuery,
            skipped: Set<String>,
            wanted: Int?,
        ): List<Record> {
            if (plan.inMemory && maxScan == 0) throw UnsupportedQueryException(resource.name, plan.reasons.joinToString("; "))
            val batch = if (!plan.inMemory && wanted != null) wanted + skipped.size else minOf(maxOf(wanted ?: 0, 300) + skipped.size, 1000)
            val found = mutableListOf<Record>()
            var last: DocumentSnapshot? = null
            var scanned = 0
            while (true) {
                val snapshot = read(mapping, (last?.let { query.startAfter(it) } ?: query).limit(batch))
                for (document in snapshot.documents) {
                    if (document.reference.path in skipped) continue
                    val record = mapping.toRecord(document.id, document.data)
                    if (plan.residual == null || record.matches(plan.residual)) found += record
                    if (wanted != null && found.size >= wanted) return found
                }
                if (snapshot.size() < batch) return found
                scanned += snapshot.size()
                if (plan.inMemory && scanned >= maxScan) {
                    throw UnsupportedQueryException(resource.name, "it needs more than $maxScan documents read, because ${plan.reasons.joinToString("; ")}")
                }
                last = snapshot.documents.last()
            }
        }

        override suspend fun count(resource: Resource, filter: Filter?): Long {
            resource.checkQuery(Query(filter))
            val mapping = mappingOf(resource)
            val plan = planners.getValue(resource.name).forCount(filter)
            if (plan.nothing) return 0
            val stored = if (plan.residual == null) {
                val counting = plan.query(mapping).count()
                indexed(mapping) { (transaction?.get(counting) ?: counting.get()).await() }.count
            } else {
                scan(resource, mapping, plan, plan.query(mapping), emptySet(), null).size.toLong()
            }
            // What Firestore counted is the documents as they were before this transaction's own writes.
            var delta = 0L
            for (entry in log?.inCollection(mapping.collection.path).orEmpty()) {
                fun counts(data: Data?) = data != null && (filter == null || mapping.toRecord(entry.ref.id, data).matches(filter))
                if (counts(entry.base)) delta--
                if (counts(entry.data)) delta++
            }
            return stored + delta
        }

        override suspend fun create(resource: Resource, record: Record): Record {
            val mapping = mappingOf(resource)
            val id = (record[Resource.ID] as Value.Text?)?.value ?: newId?.invoke() ?: mapping.collection.document().id
            val ref = mapping.ref(id) ?: throw IllegalArgumentException("'$id' can't be the id of a Firestore document.")
            val data = mapping.toData(record)
            if (log == null) {
                val started = TimeSource.Monotonic.markNow()
                var round = 0
                while (true) {
                    try {
                        ref.create(data).await()
                        break
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        if (e.statusCode() == "ALREADY_EXISTS") throw DuplicateIdException(resource.name, id)
                        // A transaction holding the document's lock may make Firestore abort the create: try again.
                        if (!e.conflict()) throw e
                        if (started.elapsedNow() >= transactionRetryTimeout) throw TransactionConflictException(++round, e)
                        withContext(NonCancellable) { delay(Random.nextLong(2, 10L + 5L * minOf(++round, 20))) }
                    }
                }
            } else {
                val current = current(ref)
                if (current.data != null) throw DuplicateIdException(resource.name, id)
                log.put(ref, current.base, data)
            }
            return mapping.toRecord(id, data)
        }

        override suspend fun update(resource: Resource, id: String, changes: Record): Record? {
            if (log == null) return inTransaction { it.update(resource, id, changes) }
            val mapping = mappingOf(resource)
            val ref = mapping.ref(id) ?: return null
            val current = current(ref)
            val data = current.data ?: return null
            if (resource.fields.none { it.name in changes }) return mapping.toRecord(id, data)
            val patched = mapping.patched(data, changes)
            log.put(ref, current.base, patched)
            return mapping.toRecord(id, patched)
        }

        override suspend fun delete(resource: Resource, id: String): Boolean {
            if (log == null) return inTransaction { it.delete(resource, id) }
            val ref = mappingOf(resource).ref(id) ?: return false
            val current = current(ref)
            if (current.data == null) return false
            log.put(ref, current.base, null)
            return true
        }

        override suspend fun nextValue(name: String, start: Long): Long {
            if (log == null) return inTransaction { it.nextValue(name, start) }
            require(validDocumentId(name)) { "'$name' can't name a counter: it must be a valid Firestore document id." }
            val ref = sequences.document(name)
            val current = current(ref)
            val next = (current.data?.get("value") as? Number)?.toLong()?.plus(1) ?: start
            log.put(ref, current.base, mapOf("value" to next))
            return next
        }

        override suspend fun updateIf(
            resource: Resource,
            id: String,
            condition: Filter?,
            changes: Record,
            increments: Map<String, Value>,
        ): ConditionalUpdate {
            resource.checkConditionalUpdate(condition, changes, increments)
            if (log == null) return inTransaction { it.updateIf(resource, id, condition, changes, increments) }
            val mapping = mappingOf(resource)
            val ref = mapping.ref(id) ?: return ConditionalUpdate.NotFound
            val current = current(ref)
            val data = current.data ?: return ConditionalUpdate.NotFound
            val record = mapping.toRecord(id, data)
            if (condition != null && !record.matches(condition)) return ConditionalUpdate.ConditionFailed
            val written = LinkedHashMap<String, Value?>(changes)
            // A null field stays null.
            for ((field, amount) in increments) written[field] = record[field]?.plus(amount)
            if (resource.fields.none { it.name in written }) return ConditionalUpdate.Updated(record)
            val patched = mapping.patched(data, written)
            log.put(ref, current.base, patched)
            return ConditionalUpdate.Updated(mapping.toRecord(id, patched))
        }

        override suspend fun <T> transaction(block: suspend (DataSource) -> T): T {
            if (log == null) return inTransaction(block)
            // A nested transaction logs over its parent's writes: dropping the child's log rolls it back.
            val child = Session(transaction, WriteLog(log))
            return block(child).also { log.absorb(child.log!!) }
        }
    }
}

/** A query Firestore can't answer as asked, and that this source won't answer by reading too much in memory. */
public class UnsupportedQueryException(
    /** The resource's name. */
    public val resource: String,
    /** Why the query can't be answered. */
    public val reason: String,
) : InvalidQueryException("Firestore can't answer this query on '$resource': $reason.")

/**
 * Firestore refused a query for want of a composite index. The message is Firestore's, with its link to create the
 * index; deploy it (with the Firebase CLI's `firestore.indexes.json`, or the console) and the query works.
 */
public class MissingIndexException(
    /** The resource's name. */
    public val resource: String,
    message: String,
) : DataException(message)

/** A transaction kept conflicting with others and was given up after [attempts] runs. */
public class TransactionConflictException(
    /** How many times the transaction ran. */
    public val attempts: Int,
    cause: Throwable,
) : DataException("The transaction kept conflicting with others and was given up after $attempts attempts.") {
    init {
        initCause(cause)
    }
}

/** How many times Firestore's client runs a transaction before [FirestoreDataSource] starts it over. */
private const val TRANSACTION_ATTEMPTS = 25

/** Whether this error, or what caused it, is Firestore aborting a transaction over a conflict. */
private fun Throwable.conflict(): Boolean =
    statusCode() == "ABORTED" || generateSequence(this) { it.cause?.takeIf { cause -> cause !== it } }.any { it.message?.contains("too many retries") == true }

/** Runs [block], turning Firestore's "needs an index" refusal into [MissingIndexException]. */
private inline fun <T> indexed(mapping: DocumentMapping, block: () -> T): T = try {
    block()
} catch (e: Throwable) {
    if (e.statusCode() == "FAILED_PRECONDITION" && e.message.orEmpty().contains("index", ignoreCase = true)) {
        throw MissingIndexException(mapping.resource.name, e.message.orEmpty())
    }
    throw e
}

/** The gRPC status code of this Firestore error or of what caused it, such as `ABORTED`, or `null` if it has none. */
private fun Throwable.statusCode(): String? {
    var error: Throwable? = this
    while (error != null) {
        when (error) {
            is ApiException -> return error.statusCode.code.name
            is FirestoreException -> error.status?.code?.name?.let { return it }
            is StatusRuntimeException -> return error.status.code.name
        }
        error = error.cause?.takeIf { it !== error }
    }
    return null
}

/** Waits for this future without blocking a thread. */
private suspend fun <T> ApiFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
    ApiFutures.addCallback(
        this,
        object : ApiFutureCallback<T> {
            override fun onSuccess(result: T) = continuation.resume(result)

            override fun onFailure(t: Throwable) = continuation.resumeWithException(t)
        },
        Executor { it.run() },
    )
    continuation.invokeOnCancellation { cancel(false) }
}
