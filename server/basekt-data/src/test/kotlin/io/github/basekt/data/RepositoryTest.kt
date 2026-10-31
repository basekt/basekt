package io.github.basekt.data

import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
import io.github.basekt.schema.model
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Two made-up models: library copies and pottery kiln firing runs. */
@Serializable
private data class LibraryCopy(
    val id: String? = null,
    val callNumber: String,
    val available: Int = 1,
    val state: CopyState = CopyState.SHELVED,
)

@Serializable
private enum class CopyState {
    @SerialName("shelved") SHELVED,
    @SerialName("loaned") LOANED,
}

@Serializable
private data class KilnRun(
    val id: String? = null,
    val cone: Int,
    val clayKg: Value.Decimal,
    val notes: String? = null,
)

class RepositoryTest {
    private val copies = model<LibraryCopy>("copies")
    private val runs = model<KilnRun>("kilnRuns")

    @Test
    fun `conditional writes refuse sources without ConditionalWrites and writing id`() = runBlocking {
        val plain = Repository(copies, PlainSource())
        assertFailsWith<UnsupportedOperationException> {
            plain.updateIf("c1") { increment(LibraryCopy::available, -1) }
        }
        assertFailsWith<UnsupportedOperationException> {
            plain.updateWhere("c1", LibraryCopy(callNumber = "QA76")) { LibraryCopy::available eq 1 }
        }

        val recording = RecordingConditionalSource()
        val kilnRepo = Repository(runs, recording)
        assertFailsWith<InvalidQueryException> {
            kilnRepo.updateIf("r1") { set(KilnRun::id, "r2") }
        }

        kilnRepo.updateIf("r1") {
            where { KilnRun::cone gte 6 }
            increment(KilnRun::clayKg, Value.Decimal.of("2.5"))
            set(KilnRun::notes, null)
        }
        assertEquals(Filter.Compare("cone", Comparison.GTE, Value.Integer(6)), recording.lastCondition)
        assertEquals(mapOf<String, Value?>("notes" to null), recording.lastChanges)
        assertEquals(mapOf<String, Value>("clayKg" to Value.Decimal.of("2.5")), recording.lastIncrements)
    }

    private open class PlainSource : DataSource {
        override suspend fun get(resource: Resource, id: String): Record? = null
        override suspend fun getMany(resource: Resource, ids: Collection<String>): Map<String, Record> = emptyMap()
        override suspend fun list(resource: Resource, query: Query): Page = Page(emptyList(), null)
        override suspend fun create(resource: Resource, record: Record): Record = record
        override suspend fun update(resource: Resource, id: String, changes: Record): Record? = null
        override suspend fun delete(resource: Resource, id: String): Boolean = false
        override suspend fun <T> transaction(block: suspend (DataSource) -> T): T = block(this)
    }

    private class RecordingConditionalSource : PlainSource(), ConditionalWrites {
        var lastCondition: Filter? = null
        var lastChanges: Record = emptyMap()
        var lastIncrements: Map<String, Value> = emptyMap()

        override suspend fun updateIf(
            resource: Resource,
            id: String,
            condition: Filter?,
            changes: Record,
            increments: Map<String, Value>,
        ): ConditionalUpdate {
            resource.checkConditionalUpdate(condition, changes, increments)
            lastCondition = condition
            lastChanges = changes
            lastIncrements = increments
            return ConditionalUpdate.NotFound
        }
    }
}
