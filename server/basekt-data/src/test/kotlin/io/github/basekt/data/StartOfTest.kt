package io.github.basekt.data

import io.github.basekt.schema.TimeBucket
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class StartOfTest {
    private fun at(text: String) = Instant.parse(text)

    @Test
    fun `buckets start at midnight in UTC by default`() {
        assertEquals(at("2026-02-16T00:00:00Z"), startOf(TimeBucket.DAY, at("2026-02-16T22:00:00Z")))
        assertEquals(at("2026-12-28T00:00:00Z"), startOf(TimeBucket.WEEK, at("2027-01-01T00:00:00Z")))
        assertEquals(at("2026-12-01T00:00:00Z"), startOf(TimeBucket.MONTH, at("2026-12-31T23:59:59Z")))
        assertEquals(at("2026-01-01T00:00:00Z"), startOf(TimeBucket.YEAR, at("2026-07-04T12:00:00Z")))
    }

    @Test
    fun `buckets follow a zone's calendar, daylight saving included`() {
        val manila = ZoneId.of("Asia/Manila")
        // 07:30 in Manila on the 16th.
        assertEquals(at("2026-02-15T16:00:00Z"), startOf(TimeBucket.DAY, at("2026-02-15T23:30:00Z"), manila))
        val newYork = ZoneId.of("America/New_York")
        // New York's clocks go forward on 8 March 2026: midnight is UTC-5 that day and UTC-4 the next.
        assertEquals(at("2026-03-08T05:00:00Z"), startOf(TimeBucket.DAY, at("2026-03-08T20:00:00Z"), newYork))
        assertEquals(at("2026-03-09T04:00:00Z"), startOf(TimeBucket.DAY, at("2026-03-09T20:00:00Z"), newYork))
        assertEquals(at("2026-03-01T05:00:00Z"), startOf(TimeBucket.MONTH, at("2026-03-31T20:00:00Z"), newYork))
    }
}
