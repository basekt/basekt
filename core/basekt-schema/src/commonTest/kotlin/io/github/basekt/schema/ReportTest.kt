package io.github.basekt.schema

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable
private data class Hive(val id: String? = null, val apiary: String, val kilos: Long)

class ReportTest {
    private val greenhouse = schema {
        resource("plants") {
            enum("bed", listOf("north", "south"))
            int("height")
            report("plantsPerBed") { groupBy("bed") }
            report("plants", Chart.NUMBER)
            report("tallestPerBed", Chart.PIE) { groupBy("bed"); max("height"); limit(5) }
            report("tallNorth", Chart.NUMBER) { where("bed", "north"); where("height", ReportOperator.GTE, "100"); whereNull("height", empty = false) }
        }
    }

    private val observatory = schema {
        resource("sightings") {
            long("night")
            decimal("magnitude")
            string("observer") { hidden() }
            instant("seenAt")
            report("brightnessByNight", Chart.LINE) { groupBy("night"); avg("magnitude") }
            report("sightingsPerWeek", Chart.LINE) { groupBy("seenAt", TimeBucket.WEEK); limit(52) }
            report("brightNights", Chart.BAR) {
                groupBy("seenAt", TimeBucket.DAY, timeZone = "America/Santiago"); where("night", ReportOperator.IN, "1", "2")
                range("seenAt", 30, TimeBucket.DAY)
            }
        }
    }

    @Test
    fun `reports are schema data with a usual order per chart`() {
        val plants = greenhouse.resource("plants")!!.reports
        assertEquals(listOf("plantsPerBed", "plants", "tallestPerBed", "tallNorth"), plants.map { it.name })
        assertEquals(
            listOf(
                ReportFilter("bed", ReportOperator.EQ, listOf("north")),
                ReportFilter("height", ReportOperator.GTE, listOf("100")),
                ReportFilter("height", ReportOperator.NULL, listOf("false")),
            ),
            plants[3].filters,
        )
        assertEquals(Report("tallestPerBed", Chart.PIE, "bed", ReportFunction.MAX, "height", limit = 5), plants[2])
        assertEquals(ReportOrder.VALUE_DESC, plants[0].orderOrDefault)
        val (byNight, perWeek, brightNights) = observatory.resource("sightings")!!.reports
        assertEquals("America/Santiago", brightNights.timeZone)
        assertEquals(ReportRange("seenAt", 30, TimeBucket.DAY), brightNights.range)
        assertEquals(listOf(ReportFilter("night", ReportOperator.IN, listOf("1", "2"))), brightNights.filters)
        assertEquals(ReportOrder.GROUP, byNight.orderOrDefault)
        assertEquals(null, byNight.bucket)
        assertEquals(Report("sightingsPerWeek", Chart.LINE, "seenAt", limit = 52, bucket = TimeBucket.WEEK), perWeek)
        assertEquals(greenhouse, Schema.fromJson(greenhouse.toJson()))
        assertEquals(observatory, Schema.fromJson(observatory.toJson()))
    }

    @Test
    fun `a report must fit its resource`() {
        fun problems(block: ReportBuilder.() -> Unit, chart: Chart = Chart.BAR): List<String> =
            assertFailsWith<InvalidSchemaException> {
                schema {
                    resource("sightings") {
                        long("night")
                        string("observer") { hidden() }
                        report("chart", chart, block)
                    }
                }
            }.problems.map { it.message }

        assertTrue(problems({}).single().contains("needs a field to group by"))
        assertTrue(problems({ groupBy("night") }, Chart.NUMBER).single().contains("has no groups"))
        assertTrue(problems({ groupBy("observer") }).single().contains("doesn't exist"))
        assertTrue(problems({ groupBy("night"); sum("observer") }).single().contains("doesn't exist"))
        assertTrue(problems({ groupBy("night"); max("night"); limit(0) }).single().contains("from 1 to"))
        assertTrue(problems({ groupBy("night", TimeBucket.MONTH) }).single().contains("Only an instant field"))
        assertTrue(problems({ groupBy("night"); range("night", 7, TimeBucket.DAY) }).single().contains("needs an instant field"))
        assertTrue(problems({ groupBy("night"); timeZone("Asia/Manila") }).single().contains("only applies"))
        assertTrue(problems({ groupBy("night"); where("night", "many") }).single().contains("not a valid value"))
        assertTrue(problems({ groupBy("night"); where("observer", "ann") }).single().contains("doesn't exist"))
        assertTrue(problems({ groupBy("night"); where("night", ReportOperator.EQ, "1", "2") }).single().contains("takes one value"))
        assertTrue(problems({ groupBy("night"); where("night", ReportOperator.NULL, "maybe") }).single().contains("true or false"))
        assertTrue(problems({ groupBy("night"); where("night", ReportOperator.CONTAINS, "1") }).single().contains("string fields"))
        assertFailsWith<InvalidSchemaException> {
            schema { resource("sightings") { string("observer"); report("byObserver") { groupBy("observer"); sum("observer") } } }
        }
        assertFailsWith<InvalidSchemaException> {
            schema { resource("sightings") { long("night"); report("n", Chart.NUMBER); report("n", Chart.NUMBER) } }
        }
        for (zone in listOf("+08:00", "Asia/Manila; DROP TABLE x")) {
            assertFailsWith<InvalidSchemaException>(zone) {
                schema { resource("sightings") { instant("seenAt"); report("n", Chart.LINE) { groupBy("seenAt", TimeBucket.DAY, zone) } } }
            }
        }
    }

    @Test
    fun `a range is a rolling window on an instant field, in the report's zone`() {
        val ledger = schema {
            resource("deposits") {
                instant("at")
                decimal("amount")
                report("lastQuarter", Chart.NUMBER) {
                    sum("amount"); range("at", 3, TimeBucket.MONTH); timeZone("Europe/Lisbon"); compare(ReportCompare.PREVIOUS_PERIOD)
                }
            }
        }
        val report = ledger.resource("deposits")!!.reports.single()
        assertEquals(ReportRange("at", 3, TimeBucket.MONTH), report.range)
        assertEquals("Europe/Lisbon", report.timeZone)
        assertEquals(ReportCompare.PREVIOUS_PERIOD, report.compare)
        assertEquals(ledger, Schema.fromJson(ledger.toJson()))
        assertFailsWith<InvalidSchemaException> {
            schema { resource("deposits") { instant("at"); report("never", Chart.NUMBER) { range("at", 0, TimeBucket.DAY) } } }
        }
        assertFailsWith<InvalidSchemaException> {
            schema { resource("deposits") { instant("at"); report("alone", Chart.NUMBER) { compare(ReportCompare.PREVIOUS_YEAR) } } }
        }
        assertFailsWith<InvalidSchemaException> {
            schema {
                resource("deposits") {
                    instant("at"); string("kind")
                    report("slices", Chart.PIE) { groupBy("kind"); range("at", 7, TimeBucket.DAY); compare(ReportCompare.PREVIOUS_PERIOD) }
                }
            }
        }
    }

    @Test
    fun `a typed model declares reports too`() {
        val hives = model<Hive>("hives") { report("honeyPerApiary") { groupBy("apiary"); sum("kilos") } }
        assertEquals(Report("honeyPerApiary", Chart.BAR, "apiary", ReportFunction.SUM, "kilos"), hives.resource.reports.single())
        assertFailsWith<InvalidSchemaException> { schema { resource<Hive>("hives") { report("honey") { groupBy("apiary"); sum("apiary") } } } }
    }
}
