package io.github.basekt.data.exposed

import io.github.basekt.data.Query
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import io.github.basekt.schema.schema
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.condition.EnabledIf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/** A made-up kite shop's first schema: kites with a short name and two colours. */
private val kitesV1: Schema = schema {
    resource("kites") {
        string("name") { required(); length(max = 10) }
        enum("color", listOf("red", "blue"))
        string("batch")
    }
}

/** The kite shop a release later: longer names, a new colour, tails, a unique maker code, and the flyers of each kite. */
private val kitesV2: Schema = schema {
    resource("kites") {
        string("name") { required(); length(max = 40) }
        enum("color", listOf("red", "blue", "turquoise"))
        string("batch")
        int("tails")
        string("maker") { unique() }
    }
    resource("flyers") {
        string("name") { required() }
        reference("kiteId", "kites")
    }
}

/** A made-up tide log, whose second release stops recording the moon phase and makes each station's code unique. */
private val tidesV1: Schema = schema {
    resource("tides") {
        string("station") { required() }
        string("moonPhase")
    }
}

private val tidesV2: Schema = schema {
    resource("tides") {
        string("station") { required(); unique() }
    }
}

/** [ExposedDataSource.migrate] on one database; each test's tables get their own prefix. */
abstract class MigrationContract {
    protected abstract fun database(): Database

    private fun source(schema: Schema, prefix: String) = ExposedDataSource(database(), schema, TableMapping(tablePrefix = prefix))

    private fun text(value: String) = Value.Text(value)

    @Test
    fun `a new release's tables, columns, wider texts and indexes are added, and the data stays`(): Unit = runBlocking {
        val prefix = "m${System.nanoTime()}_"
        val first = source(kitesV1, prefix)
        assertTrue(first.migrate().applied.isNotEmpty())
        assertEquals(MigrationReport(emptyList(), emptyList()), first.migrate())
        val kites = kitesV1.resource("kites")!!
        first.create(kites, mapOf(Resource.ID to text("k1"), "name" to text("Delta"), "color" to text("red")))

        val second = source(kitesV2, prefix)
        val report = second.migrate()
        assertTrue(report.applied.any { it.contains("tails", ignoreCase = true) }, report.applied.joinToString("\n"))
        assertEquals(emptyList(), report.notes)
        val kites2 = kitesV2.resource("kites")!!
        // The old row is there, with nothing in the new columns.
        val old = second.get(kites2, "k1")!!
        assertEquals(text("Delta"), old["name"])
        assertEquals(null, old["tails"])
        // The widened columns hold what the new release allows.
        second.create(kites2, mapOf(Resource.ID to text("k2"), "name" to text("A very long kite name, over ten"), "color" to text("turquoise"), "maker" to text("MK-1")))
        second.create(kitesV2.resource("flyers")!!, mapOf("name" to text("Ana"), "kiteId" to text("k2")))
        // The new unique index holds.
        assertFails { second.create(kites2, mapOf(Resource.ID to text("k3"), "name" to text("Copy"), "maker" to text("MK-1"))) }
        assertEquals(2, second.list(kites2, Query()).items.size)
        // Once up to date, there is nothing to do.
        assertEquals(MigrationReport(emptyList(), emptyList()), second.migrate())
    }

    @Test
    fun `a dropped field's column is kept and reported, and a unique index over duplicates is refused`(): Unit = runBlocking {
        val prefix = "t${System.nanoTime()}_"
        val first = source(tidesV1, prefix)
        first.migrate()
        val tides = tidesV1.resource("tides")!!
        first.create(tides, mapOf("station" to text("North"), "moonPhase" to text("full")))
        first.create(tides, mapOf("station" to text("North"), "moonPhase" to text("new")))

        // Two rows share a station, so the new unique index can't be built: the migration fails rather than lose one.
        assertFails { source(tidesV2, prefix).migrate() }
        val kept = first.list(tides, Query()).items
        assertEquals(2, kept.size)
        first.delete(tides, (kept.first()[Resource.ID] as Value.Text).value)

        val report = source(tidesV2, prefix).migrate()
        assertEquals(1, report.notes.size)
        assertTrue("moonPhase" in report.notes.single(), report.notes.single())
        assertTrue(report.applied.single().contains("UNIQUE", ignoreCase = true), report.applied.joinToString())
    }
}

class H2MigrationTest : MigrationContract() {
    private val database = h2()

    override fun database(): Database = database
}

/** On a real Postgres, as [PostgresContractTest] finds one. */
@EnabledIf("io.github.basekt.data.exposed.DatabasesKt#postgresAvailable")
class PostgresMigrationTest : MigrationContract() {
    override fun database(): Database = postgres()
}
