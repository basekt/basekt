package io.github.basekt.data.exposed

import io.github.basekt.data.AggregateFunction
import io.github.basekt.data.AggregateQuery
import io.github.basekt.data.Comparison
import io.github.basekt.data.DataException
import io.github.basekt.data.Direction
import io.github.basekt.data.DuplicateIdException
import io.github.basekt.data.Filter
import io.github.basekt.data.InvalidQueryException
import io.github.basekt.data.Measure
import io.github.basekt.data.Query
import io.github.basekt.data.Sort
import io.github.basekt.schema.IdSpec
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.TimeBucket
import io.github.basekt.schema.Value
import io.github.basekt.schema.schema
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.condition.EnabledIf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A made-up community garden whose member register another program keeps: gardeners under numbers the database
 * hands out, and each gardener's harvest per season, keyed by the two together.
 */
private val garden: Schema = schema {
    resource("gardeners") {
        string("name") { required() }
        instant("joinedAt")
        int("plots")
    }
    resource("harvests") {
        reference("gardenerId", "gardeners") { required() }
        string("season") { required() }
        decimal("weight")
        keyedBy("gardenerId", "season")
    }
}

/**
 * A made-up network of tide gauges: stations under the numbers printed on them, and the readings a logger writes,
 * which the application only shows.
 */
private val tides: Schema = schema {
    resource("stations") {
        naturalId()
        string("label") { required() }
    }
    resource("readings") {
        reference("stationId", "stations")
        long("level")
        instant("takenAt")
    }
}

/** Resources kept in tables that already exist, on one database. Each test makes its own tables. */
abstract class MappedTableContract {
    protected abstract fun database(): Database

    /** The column definition of a primary key the database generates, as the dialect writes it. */
    protected abstract val generatedKey: String

    private fun sql(vararg statements: String) = transaction(database()) { statements.forEach { exec(it) } }

    private fun Record.id() = (this[Resource.ID] as Value.Text).value

    private fun text(value: String) = Value.Text(value)

    private fun prefix() = "x${System.nanoTime()}_"

    /** The garden's tables, as the other program made them, and the source mapped onto them. */
    private fun gardenSource(p: String): ExposedDataSource {
        sql(
            "CREATE TABLE ${p}plot_holders (holder_no $generatedKey, full_name VARCHAR(100) NOT NULL, " +
                "joined_on TIMESTAMP, plot_count BIGINT, notes VARCHAR(200) DEFAULT 'none')",
            "CREATE TABLE ${p}crop_log (holder_no BIGINT NOT NULL, season VARCHAR(40) NOT NULL, kg NUMERIC(10, 2), " +
                "PRIMARY KEY (holder_no, season))",
        )
        val mapping = TableMapping(
            tables = mapOf(
                "gardeners" to MappedTable(
                    table = "${p}plot_holders",
                    columns = mapOf("name" to "full_name", "joinedAt" to "joined_on", "plots" to "plot_count"),
                    types = mapOf("joinedAt" to SqlType.TIMESTAMP, "plots" to SqlType.BIGINT),
                    key = MappedKey.Column("holder_no", SqlType.BIGINT, generated = true),
                ),
                "harvests" to MappedTable(
                    table = "${p}crop_log",
                    columns = mapOf("gardenerId" to "holder_no", "weight" to "kg"),
                    types = mapOf("gardenerId" to SqlType.BIGINT),
                ),
            ),
        )
        return ExposedDataSource(database(), garden, mapping)
    }

    /** The tide logger's tables, and the source mapped onto them, the readings read-only. */
    private fun tidesSource(p: String): ExposedDataSource {
        sql(
            "CREATE TABLE ${p}gauge (station_no INTEGER PRIMARY KEY, label VARCHAR(80) NOT NULL)",
            "CREATE TABLE ${p}gauge_reading (reading_uuid UUID PRIMARY KEY, station_no INTEGER, level_cm BIGINT, " +
                "taken_at TIMESTAMP WITH TIME ZONE)",
        )
        val mapping = TableMapping(
            tables = mapOf(
                "stations" to MappedTable("${p}gauge", key = MappedKey.Column("station_no", SqlType.INTEGER)),
                "readings" to MappedTable(
                    table = "${p}gauge_reading",
                    columns = mapOf("stationId" to "station_no", "level" to "level_cm", "takenAt" to "taken_at"),
                    types = mapOf("stationId" to SqlType.INTEGER),
                    key = MappedKey.Column("reading_uuid", SqlType.UUID),
                    readOnly = true,
                ),
            ),
        )
        return ExposedDataSource(database(), tides, mapping)
    }

    @Test
    fun `a renamed table and columns with a generated key are read and written as the schema's`(): Unit = runBlocking {
        val p = prefix()
        val source = gardenSource(p)
        val gardeners = garden.resource("gardeners")!!
        val joined = Instant.parse("2026-03-01T08:30:00Z")
        val ana = source.create(gardeners, mapOf("name" to text("Ana"), "joinedAt" to Value.Timestamp(joined), "plots" to Value.Integer(2)))
        val ben = source.create(gardeners, mapOf("name" to text("Ben")))
        // The database numbered them.
        assertEquals(ana.id().toLong() + 1, ben.id().toLong())
        assertEquals(text("Ana"), ana["name"])
        assertEquals(Value.Timestamp(joined), ana["joinedAt"])
        assertEquals(Value.Integer(2), ana["plots"])
        assertEquals(setOf(Resource.ID, "name", "joinedAt", "plots"), ana.keys)

        // The other program's column keeps its default, and the values are in the columns the mapping names.
        transaction(database()) {
            exec("SELECT full_name, plot_count, notes FROM ${p}plot_holders WHERE holder_no = ${ana.id()}") { rows ->
                assertTrue(rows.next())
                assertEquals("Ana", rows.getString(1))
                assertEquals(2L, rows.getLong(2))
                assertEquals("none", rows.getString(3))
            }
        }

        assertEquals(Value.Integer(5), source.update(gardeners, ana.id(), mapOf("plots" to Value.Integer(5)))!!["plots"])
        assertEquals(setOf(ana.id(), ben.id()), source.getMany(gardeners, listOf(ana.id(), ben.id(), "not-a-number", "007")).keys)
        assertNull(source.get(gardeners, "not-a-number"))
        assertEquals(listOf(ben.id()), source.list(gardeners, Query(Filter.Compare(Resource.ID, Comparison.EQ, text(ben.id())))).items.map { it.id() })
        assertEquals(0, source.list(gardeners, Query(Filter.Compare(Resource.ID, Comparison.EQ, text("ana")))).items.size)
        assertFailsWith<InvalidQueryException> { source.list(gardeners, Query(Filter.Contains(Resource.ID, "1"))) }

        // A create can't choose a number the database hands out.
        assertFailsWith<DataException> { source.create(gardeners, mapOf(Resource.ID to text("99"), "name" to text("Cy"))) }

        // Ids sort as numbers, and a cursor walks them.
        val more = (1..10).map { source.create(gardeners, mapOf("name" to text("G$it"), "plots" to Value.Integer((it % 3).toLong()))) }
        val all = listOf(ana, ben) + more
        val walked = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = source.list(gardeners, Query(sort = listOf(Sort("plots", Direction.DESC)), limit = 4, cursor = cursor))
            walked += page.items.map { it.id() }
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(all.map { it.id() }.toSet(), walked.toSet())
        assertEquals(all.size, walked.size)
        assertEquals(all.map { it.id().toLong() }.sorted(), source.list(gardeners, Query(limit = 100)).items.map { it.id().toLong() })

        // Grouping by day reads the UTC timestamps as UTC.
        val days = source.aggregate(
            gardeners,
            AggregateQuery(groupBy = listOf("joinedAt"), measures = listOf(Measure(AggregateFunction.COUNT)), buckets = mapOf("joinedAt" to TimeBucket.DAY)),
        )
        assertTrue(days.groups.any { it.key["joinedAt"] == Value.Timestamp(Instant.parse("2026-03-01T00:00:00Z")) }, days.toString())

        assertTrue(source.delete(gardeners, ben.id()))
        assertNull(source.get(gardeners, ben.id()))
        assertEquals(false, source.delete(gardeners, "nobody"))
    }

    @Test
    fun `a key of two columns is read as a keyedBy id`(): Unit = runBlocking {
        val p = prefix()
        val source = gardenSource(p)
        val gardeners = garden.resource("gardeners")!!
        val harvests = garden.resource("harvests")!!
        val ana = source.create(gardeners, mapOf("name" to text("Ana"))).id()
        val ben = source.create(gardeners, mapOf("name" to text("Ben"))).id()

        fun harvest(gardener: String, season: String, kg: String) = mapOf(
            Resource.ID to text(IdSpec.keyOf(listOf(gardener, season))),
            "gardenerId" to text(gardener),
            "season" to text(season),
            "weight" to Value.Decimal.of(kg),
        )
        val first = source.create(harvests, harvest(ana, "2026 spring", "12.5"))
        assertEquals("$ana~2026 spring", first.id())
        assertEquals(text(ana), first["gardenerId"])
        source.create(harvests, harvest(ana, "2026/autumn", "3"))
        source.create(harvests, harvest(ben, "2026 spring", "7.25"))
        // The key is the table's own: the same two values again are a duplicate.
        assertFailsWith<DuplicateIdException> { source.create(harvests, harvest(ana, "2026 spring", "1")) }
        // A create without the id makes it from the key fields, as the server would.
        val noId = source.create(harvests, harvest(ben, "2025 winter", "2") - Resource.ID)
        assertEquals("$ben~2025 winter", noId.id())
        assertFailsWith<DataException> { source.create(harvests, mapOf("gardenerId" to text(ben))) }

        // The separator and the slash are percent-encoded in the id, and read back.
        val autumn = "$ana~2026%2Fautumn"
        assertEquals(Value.Decimal.of("3"), source.get(harvests, autumn)!!["weight"])
        assertNull(source.get(harvests, "$ana~2027 spring"))
        assertNull(source.get(harvests, "just-one-part"))
        assertNull(source.get(harvests, "x~2026 spring"))
        assertEquals(setOf(first.id(), autumn), source.getMany(harvests, listOf(first.id(), autumn, "x~y")).keys)

        assertEquals(Value.Decimal.of("13"), source.update(harvests, first.id(), mapOf("weight" to Value.Decimal.of("13")))!!["weight"])
        assertEquals(listOf(autumn), source.list(harvests, Query(Filter.Compare(Resource.ID, Comparison.EQ, text(autumn)))).items.map { it.id() })
        assertEquals(3, source.list(harvests, Query(Filter.Compare(Resource.ID, Comparison.NE, text(autumn)))).items.size)

        // Pages in id order, which is the key columns' order, and walking them by cursor.
        val walked = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = source.list(harvests, Query(limit = 1, cursor = cursor))
            walked += page.items.map { it.id() }
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(source.list(harvests).items.map { it.id() }, walked)
        assertEquals(4, walked.toSet().size)
        val descending = source.list(harvests, Query(sort = listOf(Sort(Resource.ID, Direction.DESC)), limit = 2))
        val rest = source.list(harvests, Query(sort = listOf(Sort(Resource.ID, Direction.DESC)), limit = 10, cursor = descending.nextCursor))
        assertEquals(walked.reversed(), (descending.items + rest.items).map { it.id() })

        val totals = source.aggregate(harvests, AggregateQuery(groupBy = listOf("gardenerId"), measures = listOf(Measure(AggregateFunction.SUM, "weight"))))
        assertEquals(
            mapOf(ana to Value.Decimal.of("16"), ben to Value.Decimal.of("9.25")),
            totals.groups.associate { (it.key["gardenerId"] as Value.Text).value to it.values["sum_weight"] },
        )
        assertEquals(2L, source.count(harvests, Filter.Compare("gardenerId", Comparison.EQ, text(ana))))

        assertTrue(source.delete(harvests, autumn))
        assertEquals(false, source.delete(harvests, autumn))
    }

    @Test
    fun `a read-only table is read with its own types and refuses writes`(): Unit = runBlocking {
        val p = prefix()
        val source = tidesSource(p)
        val stations = tides.resource("stations")!!
        val readings = tides.resource("readings")!!
        val north = source.create(stations, mapOf(Resource.ID to text("12"), "label" to text("North pier")))
        assertEquals("12", north.id())
        assertFailsWith<DataException> { source.create(stations, mapOf(Resource.ID to text("north"), "label" to text("North"))) }

        val first = "3f8e2c1a-5b6d-4e7f-8a9b-0c1d2e3f4a5b"
        sql(
            "INSERT INTO ${p}gauge_reading VALUES ('$first', 12, 140, TIMESTAMP WITH TIME ZONE '2026-06-01 10:00:00+02:00')",
            "INSERT INTO ${p}gauge_reading VALUES ('9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d', 12, 95, TIMESTAMP WITH TIME ZONE '2026-06-02 23:30:00+00:00')",
            "INSERT INTO ${p}gauge_reading VALUES ('00000000-0000-4000-8000-000000000001', NULL, NULL, NULL)",
        )
        val reading = source.get(readings, first)!!
        assertEquals(text("12"), reading["stationId"])
        assertEquals(Value.Integer(140), reading["level"])
        assertEquals(Value.Timestamp(Instant.parse("2026-06-01T08:00:00Z")), reading["takenAt"])
        assertNull(source.get(readings, "not-a-uuid"))

        val high = source.list(readings, Query(Filter.Compare("level", Comparison.GT, Value.Integer(100))))
        assertEquals(listOf(first), high.items.map { it.id() })
        assertEquals(2, source.list(readings, Query(Filter.Compare("stationId", Comparison.EQ, text("12")))).items.size)
        assertEquals(0, source.list(readings, Query(Filter.Compare("stationId", Comparison.EQ, text("north")))).items.size)
        assertFailsWith<InvalidQueryException> { source.list(readings, Query(Filter.Compare("stationId", Comparison.GT, text("north")))) }
        val days = source.aggregate(
            readings,
            AggregateQuery(groupBy = listOf("takenAt"), measures = listOf(Measure(AggregateFunction.MAX, "level")), buckets = mapOf("takenAt" to TimeBucket.DAY)),
        )
        assertEquals(
            listOf(Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-06-02T00:00:00Z"), null),
            days.groups.map { (it.key["takenAt"] as Value.Timestamp?)?.value },
        )

        assertFailsWith<ReadOnlyTableException> { source.create(readings, mapOf("level" to Value.Integer(1))) }
        assertFailsWith<ReadOnlyTableException> { source.update(readings, first, mapOf("level" to Value.Integer(1))) }
        assertFailsWith<ReadOnlyTableException> { source.delete(readings, first) }
        assertFailsWith<ReadOnlyTableException> { source.updateIf(readings, first, null, emptyMap(), mapOf("level" to Value.Integer(1))) }
        assertEquals(Value.Integer(140), source.get(readings, first)!!["level"])
    }

    @Test
    fun `migrate leaves mapped tables alone and reports what doesn't match`(): Unit = runBlocking {
        val p = prefix()
        sql(
            "CREATE TABLE ${p}gauge (station_no INTEGER PRIMARY KEY, label VARCHAR(80), code VARCHAR(10) NOT NULL)",
        )
        val mismatched = TableMapping(
            tablePrefix = p,
            tables = mapOf(
                // label may be null for a required field, and code can't be null without a default for an optional one.
                "stations" to MappedTable("${p}gauge", key = MappedKey.Column("station_no", SqlType.INTEGER)),
                // no such table yet.
                "readings" to MappedTable("${p}gauge_reading"),
            ),
        )
        val schemaWithCode = schema {
            resource("stations") {
                naturalId()
                string("label") { required() }
                string("code")
                bool("tidal")
            }
            resource("readings") { long("level") }
            resource("notes") { string("text") }
        }
        val source = ExposedDataSource(database(), schemaWithCode, mismatched)
        val report = source.migrate()
        // Only BaseKt's own tables were made: the notes resource and the sequences.
        assertTrue(report.applied.none { "gauge" in it.lowercase() }, report.applied.joinToString("\n"))
        assertTrue(report.applied.any { "${p}notes" in it.lowercase() }, report.applied.joinToString("\n"))
        val notes = report.notes.joinToString("\n")
        assertTrue(report.notes.any { "label" in it && "can be null" in it && "required" in it }, notes)
        assertTrue(report.notes.any { "code" in it && "no default" in it }, notes)
        assertTrue(report.notes.any { "no column 'tidal'" in it }, notes)
        assertTrue(report.notes.any { "${p}gauge_reading" in it && "doesn't exist" in it }, notes)
        assertEquals(4, report.notes.size, notes)

        // A column of a type the mapping can't read, and another primary key.
        sql("CREATE TABLE ${p}gauge_reading (reading_no INTEGER PRIMARY KEY, id VARCHAR(40) NOT NULL, level BOOLEAN)")
        val second = source.migrate()
        val secondNotes = second.notes.joinToString("\n")
        assertTrue(second.notes.any { "primary key is (reading_no)" in it }, secondNotes)
        assertTrue(second.notes.any { "level" in it && "BIGINT" in it }, secondNotes)

        // Nothing about the mapped tables changed.
        transaction(database()) {
            exec("SELECT COUNT(*) FROM ${p}gauge") { rows -> assertTrue(rows.next()) }
        }
        val columns = transaction(database()) {
            val meta = (connection.connection as java.sql.Connection).metaData
            val name = if (meta.storesUpperCaseIdentifiers()) "${p}gauge".uppercase() else "${p}gauge"
            meta.getColumns(null, null, name, "%").use { rows -> buildList { while (rows.next()) add(rows.getString("COLUMN_NAME").lowercase()) } }
        }
        assertEquals(setOf("station_no", "label", "code"), columns.toSet())
    }

    @Test
    fun `a matching mapped table has nothing to report`(): Unit = runBlocking {
        val p = prefix()
        val source = gardenSource(p)
        val report = source.migrate()
        assertEquals(emptyList(), report.notes)
        assertTrue(report.applied.none { "plot_holders" in it.lowercase() || "crop_log" in it.lowercase() })
    }
}

class H2MappedTableTest : MappedTableContract() {
    private val database = h2()

    override fun database(): Database = database

    override val generatedKey: String = "BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY"
}

/** On a real Postgres, as [PostgresContractTest] finds one, with a `bigserial` key. */
@EnabledIf("io.github.basekt.data.exposed.DatabasesKt#postgresAvailable")
class PostgresMappedTableTest : MappedTableContract() {
    override fun database(): Database = postgres()

    override val generatedKey: String = "BIGSERIAL PRIMARY KEY"
}

/** What the mapping checks when the source is made, before any database is touched. */
class MappingProblemsTest {
    @Test
    fun `every problem of a mapping is listed at once`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ExposedDataSource(
                h2(),
                garden,
                TableMapping(
                    indexes = listOf(IndexSpec("gardeners", "name")),
                    tables = mapOf(
                        "gardeners" to MappedTable(
                            table = "members",
                            columns = mapOf("nickname" to "nick", "plots" to "name"),
                            types = mapOf("name" to SqlType.BIGINT, "joinedAt" to SqlType.TEXT),
                            key = MappedKey.Column("member_code", SqlType.TEXT, generated = true),
                        ),
                        "harvests" to MappedTable("members", key = MappedKey.Column("id", SqlType.DECIMAL)),
                        "orchards" to MappedTable("trees"),
                    ),
                ),
            )
        }
        val expected = listOf(
            "An index on 'gardeners' can't be made: its table is mapped, and BaseKt never changes a mapped table.",
            "The mapping of 'gardeners' names the field 'nickname', which it doesn't have.",
            "'gardeners.name' is a string field, which can't be kept in a BIGINT column; it can be TEXT.",
            "'gardeners.joinedAt' is an instant field, which can't be kept in a TEXT column; it can be TIMESTAMPTZ or TIMESTAMP.",
            "The database can generate only an INTEGER or BIGINT key, so the key of 'gardeners' can't be a generated TEXT.",
            "The fields 'gardeners.name' and 'gardeners.plots' share the column 'name'.",
            "The key of 'harvests' can't be a DECIMAL column; it can be TEXT or INTEGER or BIGINT or UUID.",
            "The mapping names the resource 'orchards', which the schema doesn't have.",
            "The resources 'gardeners' and 'harvests' share the table 'members'.",
        )
        val message = error.message!!
        assertTrue(message.startsWith("The table mapping doesn't fit the schema:\n"), message)
        for (line in expected) assertTrue("- $line" in message.lines(), "missing: $line\nin:\n$message")
        assertEquals(expected.size + 1, message.lines().size, message)
    }

    @Test
    fun `ids that the schema makes can't be generated, and key fields need a keyedBy resource`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ExposedDataSource(
                h2(),
                tides,
                TableMapping(
                    tables = mapOf(
                        "stations" to MappedTable("gauge", key = MappedKey.Column("station_no", SqlType.INTEGER, generated = true)),
                        "readings" to MappedTable("gauge_reading", columns = mapOf("level" to "id"), key = MappedKey.Fields),
                    ),
                ),
            )
        }
        val message = error.message!!
        assertTrue("- 'stations' has natural ids, so its key can't be generated by the database." in message.lines(), message)
        assertTrue("- The key of 'readings' is its key fields' columns, but 'readings' isn't keyedBy any fields." in message.lines(), message)
        assertTrue("- 'readings.level' is a long field, which can't be kept in an INTEGER column; it can be BIGINT." !in message.lines(), message)
    }

    @Test
    fun `a long field can't be kept in an int column`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ExposedDataSource(
                h2(),
                tides,
                TableMapping(tables = mapOf("readings" to MappedTable("gauge_reading", types = mapOf("level" to SqlType.INTEGER)))),
            )
        }
        assertTrue("- 'readings.level' is a long field, which can't be kept in an INTEGER column; it can be BIGINT." in error.message!!.lines(), error.message)
    }
}
