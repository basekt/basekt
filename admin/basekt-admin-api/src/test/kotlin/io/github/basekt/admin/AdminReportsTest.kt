package io.github.basekt.admin

import io.github.basekt.schema.Chart
import io.github.basekt.schema.ReportFunction
import io.github.basekt.schema.ReportOrder
import io.github.basekt.schema.TimeBucket
import io.github.basekt.schema.schema
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** A made-up kennel: walkers log walks, and only the keeper may read them. */
private val kennelSchema = schema {
    resource("walks") {
        enum("dog", listOf("rex", "bo", "fido"))
        int("minutes") { required() }
        report("minutesPerDog", Chart.BAR) { groupBy("dog"); sum("minutes"); limit(2) }
        report("walks", Chart.NUMBER)
        permissions { read("keeper"); create("walker", "keeper") }
    }
}

/** A made-up weather station: anyone reads its readings, charted per hour of the day and per day. */
private val stationSchema = schema {
    resource("readings") {
        int("hour") { required() }
        decimal("celsius") { required() }
        instant("takenAt")
        report("temperatureByHour", Chart.LINE) { groupBy("hour"); avg("celsius") }
        report("temperatureByDay", Chart.LINE) { groupBy("takenAt", TimeBucket.DAY); avg("celsius"); limit(7) }
        permissions { read(everyone); create("sensor") }
    }
}

class AdminReportsTest {
    private suspend fun io.ktor.client.HttpClient.reports(resource: String, vararg roles: String): List<AdminReport>? {
        val response = get("/admin/api/schema") { asUser(*roles) }
        val schema = Json.decodeFromString(AdminSchema.serializer(), response.json().toString())
        return schema.resources.firstOrNull { it.name == resource }?.reports
    }

    @Test
    fun `reports reach only callers who may read, and their parameters give the chart's numbers`() = testApplication {
        admin(kennelSchema)
        for ((dog, minutes) in listOf("rex" to 30, "rex" to 20, "bo" to 45, "fido" to 5)) {
            assertEquals(HttpStatusCode.Created, client.postJson("/admin/api/resources/walks/records", """{"dog": "$dog", "minutes": $minutes}""", "walker").status)
        }

        assertEquals(emptyList(), client.reports("walks", "walker"))
        val reports = client.reports("walks", "keeper")!!
        assertEquals(
            listOf(
                AdminReport("minutesPerDog", Chart.BAR, "dog", ReportFunction.SUM, "minutes", "sum_minutes", ReportOrder.VALUE_DESC, 2),
                AdminReport("walks", Chart.NUMBER, null, ReportFunction.COUNT, null, "count", ReportOrder.VALUE_DESC, 12),
            ),
            reports,
        )

        val bar = reports.first()
        val response = client.get("/admin/api/resources/walks/aggregate?groupBy=${bar.groupBy}&sum=${bar.field}&sort=-${bar.measure}&limit=${bar.limit}") {
            asUser("keeper")
        }
        val page = Json.decodeFromString(AdminAggregatePage.serializer(), response.json().toString())
        assertEquals(listOf("rex" to "50", "bo" to "45"), page.groups.map { it.key["dog"]!!.jsonPrimitive.content to it.values[bar.measure]!!.jsonPrimitive.content })
        assertEquals(true, page.hasMore)
    }

    @Test
    fun `a line is ordered by its groups, and a time series says its bucket`() = testApplication {
        admin(stationSchema)
        val (line, daily) = client.reports("readings")!!
        assertEquals(Chart.LINE, line.chart)
        assertEquals("avg_celsius", line.measure)
        assertEquals(ReportOrder.GROUP, line.order)
        assertEquals(null, line.bucket)
        assertEquals(TimeBucket.DAY, daily.bucket)
        assertEquals(7, daily.limit)
    }
}
