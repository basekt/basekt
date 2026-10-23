package io.github.basekt.schema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A chart of a resource's records for an admin panel: [function] of [field] over the records, in one group per value
 * of [groupBy]. It is presentation data over the resource's aggregates, so whoever may read the resource may see it,
 * and the caller's read scope applies to its numbers.
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

    /** Makes one group per value of [field]. */
    public fun groupBy(field: String) {
        groupBy = field
        bucket = null
    }

    /** Makes one group per [bucket] of the instant field [field], such as one per month: a time series. */
    public fun groupBy(field: String, bucket: TimeBucket) {
        groupBy = field
        this.bucket = bucket
    }

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

    internal fun build(): Report = Report(name, chart, groupBy, function, field, order, limit, bucket)
}
