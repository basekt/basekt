package io.github.basekt.schema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A chart of a resource's records for an admin panel: [function] of [field] over the records that match [filters], in
 * one group per value of [groupBy]. It is presentation data over the resource's aggregates, so whoever may read the
 * resource may see it, and the caller's read scope applies to its numbers.
 */
@Serializable
public data class Report(
    /** The report's name, unique on its resource. A panel shows it as the chart's title. */
    val name: String,
    /** How to draw it. */
    val chart: Chart = Chart.BAR,
    /** The field whose values make the groups, or `null` for one number over every record, as [Chart.NUMBER] needs. */
    val groupBy: String? = null,
    /** What to compute for each group. */
    val function: ReportFunction = ReportFunction.COUNT,
    /** The number field to compute it over, or `null` to count records. */
    val field: String? = null,
    /** The order of the groups, or `null` for the chart's usual one: see [orderOrDefault]. */
    val order: ReportOrder? = null,
    /** The most groups to show, from 1 to [MAX_REPORT_GROUPS]. A time series shows the latest ones. */
    val limit: Int = 12,
    /** The span of time an instant [groupBy] field is grouped into, or `null` to group by its values as they are. */
    val bucket: TimeBucket? = null,
    /** The time zone whose calendar [bucket] and [range] follow, such as `Asia/Manila`, or `null` for UTC. */
    val timeZone: String? = null,
    /** The conditions a record must meet to count, all of them. */
    val filters: List<ReportFilter> = emptyList(),
    /** The rolling window of time a record must fall in to count, such as the last 14 days, or `null` for all time. */
    val range: ReportRange? = null,
    /** The earlier window to show beside [range]'s, or `null` to show it alone. A viewer can change it. */
    val compare: ReportCompare? = null,
) {
    /** The order of the groups: [order], or by group for a line and by value, largest first, otherwise. */
    public val orderOrDefault: ReportOrder
        get() = order ?: if (chart == Chart.LINE) ReportOrder.GROUP else ReportOrder.VALUE_DESC
}

/** The most groups a [Report] may show. */
public const val MAX_REPORT_GROUPS: Int = 100

/** How a [Report] is drawn. */
@Serializable
public enum class Chart {
    /** One number, without groups. */
    @SerialName("number") NUMBER,

    /** A bar per group. */
    @SerialName("bar") BAR,

    /** A line through the groups, in order. */
    @SerialName("line") LINE,

    /** A slice per group, of the whole. */
    @SerialName("pie") PIE,
}

/** What a [Report] computes for each group. */
@Serializable
public enum class ReportFunction {
    /** How many records, or how many with the field set. */
    @SerialName("count") COUNT,

    /** The total of a number field. */
    @SerialName("sum") SUM,

    /** The mean of a number field. */
    @SerialName("avg") AVG,

    /** The smallest value of a number field. */
    @SerialName("min") MIN,

    /** The largest value of a number field. */
    @SerialName("max") MAX,
}

/**
 * A span of time that an instant field's values are grouped into, in UTC: each value counts in the bucket that holds
 * it, keyed by the bucket's first instant.
 */
@Serializable
public enum class TimeBucket {
    /** A calendar day. */
    @SerialName("day") DAY,

    /** A week from Monday, as ISO 8601 counts weeks. */
    @SerialName("week") WEEK,

    /** A calendar month. */
    @SerialName("month") MONTH,

    /** A calendar year. */
    @SerialName("year") YEAR,
}

/**
 * A condition on a [Report]'s records: [field] compared by [operator] with [values], written as the API's query
 * parameters write them, such as `completed` for an enum or `2026-01-01T00:00:00Z` for an instant.
 */
@Serializable
public data class ReportFilter(
    /** The field to compare. */
    val field: String,
    /** How to compare it. */
    val operator: ReportOperator,
    /** What to compare it with: one value, several for [ReportOperator.IN], or `true` or `false` for [ReportOperator.NULL]. */
    val values: List<String>,
)

/**
 * A rolling window of time on a [Report]: the records whose instant [field] falls in the [last] spans of [unit] up to
 * now, the current one included, on the report's time zone calendar. `ReportRange("createdAt", 14, DAY)` is today and
 * the 13 days before it. Whoever draws the report turns it into dates when it draws it, so it never goes stale.
 */
@Serializable
public data class ReportRange(
    /** The instant field that must fall in the window. */
    val field: String,
    /** How many spans the window holds, from 1 to [MAX_RANGE_SPANS]. */
    val last: Int,
    /** The span the window counts in. */
    val unit: TimeBucket,
)

/** An earlier window a [Report] shows beside its range, to compare with. */
@Serializable
public enum class ReportCompare {
    /** The window of the same length just before. */
    @SerialName("previousPeriod") PREVIOUS_PERIOD,

    /** The same dates a year earlier. */
    @SerialName("previousYear") PREVIOUS_YEAR,
}

/** The most spans a [ReportRange] may hold. */
public const val MAX_RANGE_SPANS: Int = 1000

/** How a [ReportFilter] compares a field. */
@Serializable
public enum class ReportOperator {
    /** Equal to the value. */
    @SerialName("eq") EQ,

    /** Not equal to the value. */
    @SerialName("ne") NE,

    /** Greater than the value. */
    @SerialName("gt") GT,

    /** Greater than or equal to the value. */
    @SerialName("gte") GTE,

    /** Less than the value. */
    @SerialName("lt") LT,

    /** Less than or equal to the value. */
    @SerialName("lte") LTE,

    /** Equal to one of the values. */
    @SerialName("in") IN,

    /** Text that contains the value. */
    @SerialName("contains") CONTAINS,

    /** Empty when the value is `true`, set when it is `false`. */
    @SerialName("null") NULL,
}

/** The order of a [Report]'s groups. */
@Serializable
public enum class ReportOrder {
    /** Largest value first. */
    @SerialName("valueDesc") VALUE_DESC,

    /** Smallest value first. */
    @SerialName("valueAsc") VALUE_ASC,

    /** By the group's value, ascending. */
    @SerialName("group") GROUP,
}

/** Declares what a [Report] groups by and computes. */
@SchemaDsl
public class ReportBuilder internal constructor(private val name: String, private val chart: Chart) {
    private var groupBy: String? = null
    private var function = ReportFunction.COUNT
    private var field: String? = null
    private var order: ReportOrder? = null
    private var limit = 12
    private var bucket: TimeBucket? = null
    private var timeZone: String? = null
    private val filters = mutableListOf<ReportFilter>()
    private var range: ReportRange? = null
    private var compare: ReportCompare? = null

    /** Makes one group per value of [field]. */
    public fun groupBy(field: String) {
        groupBy = field
        bucket = null
    }

    /**
     * Makes one group per [bucket] of the instant field [field], such as one per month: a time series. Days, weeks,
     * months and years follow the calendar of [timeZone], a region such as `Asia/Manila`, or UTC when it is `null`.
     */
    public fun groupBy(field: String, bucket: TimeBucket, timeZone: String? = null) {
        groupBy = field
        this.bucket = bucket
        if (timeZone != null) this.timeZone = timeZone
    }

    /** Follows the calendar of [timeZone], a region such as `Asia/Manila`, for the time buckets and the range. */
    public fun timeZone(timeZone: String) {
        this.timeZone = timeZone
    }

    /** Counts only the records whose [field] equals [value], such as `where("status", "completed")`. */
    public fun where(field: String, value: String): Unit = where(field, ReportOperator.EQ, value)

    /** Counts only the records whose [field] compares by [operator] with [values]. See [ReportFilter]. */
    public fun where(field: String, operator: ReportOperator, vararg values: String) {
        filters += ReportFilter(field, operator, values.toList())
    }

    /**
     * Counts only the records whose instant [field] falls in the [last] spans of [unit] up to now, the current one
     * included: `range("createdAt", 14, TimeBucket.DAY)` is the last 14 days. A time series over [field] then shows
     * every span of the window, empty ones too. See [ReportRange].
     */
    public fun range(field: String, last: Int, unit: TimeBucket) {
        range = ReportRange(field, last, unit)
    }

    /** Shows the [compare] window beside the [range]'s by default, such as the previous period. A range is needed. */
    public fun compare(compare: ReportCompare) {
        this.compare = compare
    }

    /** Counts only the records whose [field] is empty, or set when [empty] is false. */
    public fun whereNull(field: String, empty: Boolean = true): Unit = where(field, ReportOperator.NULL, empty.toString())

    /** Counts the records, or the ones with [field] set. This is the default. */
    public fun count(field: String? = null): Unit = compute(ReportFunction.COUNT, field)

    /** Adds up the number field [field]. */
    public fun sum(field: String): Unit = compute(ReportFunction.SUM, field)

    /** Averages the number field [field]. */
    public fun avg(field: String): Unit = compute(ReportFunction.AVG, field)

    /** Takes the smallest value of the number field [field]. */
    public fun min(field: String): Unit = compute(ReportFunction.MIN, field)

    /** Takes the largest value of the number field [field]. */
    public fun max(field: String): Unit = compute(ReportFunction.MAX, field)

    /** Orders the groups by [order] instead of the chart's usual order. */
    public fun order(order: ReportOrder) {
        this.order = order
    }

    /** Shows at most [groups] groups. */
    public fun limit(groups: Int) {
        limit = groups
    }

    private fun compute(function: ReportFunction, field: String?) {
        this.function = function
        this.field = field
    }

    internal fun build(): Report = Report(name, chart, groupBy, function, field, order, limit, bucket, timeZone, filters.toList(), range, compare)
}
