import { describe, expect, it } from "vitest"
import type { AdminReport } from "@wire"
import { describeRange, describeReport, formatBucket, reportParams, reportPoints } from "@/lib/reports"
import { books, tickets } from "@/test/fixtures"

const feesByAuthor: AdminReport = {
  name: "feesByAuthor", chart: "bar", groupBy: "authorId", function: "sum", field: "fee", measure: "sum_fee", order: "valueAsc", limit: 5, bucket: null, timeZone: null, filters: [], range: null,
}
const ticketsPerMonth: AdminReport = {
  name: "ticketsPerMonth", chart: "line", groupBy: "openedAt", function: "count", field: null, measure: "count", order: "group", limit: 6, bucket: "month", timeZone: null, filters: [], range: null,
}
const pagesOverTime: AdminReport = { ...feesByAuthor, name: "pages", chart: "line", groupBy: "pages", function: "max", field: "pages", measure: "max_pages", order: "group" }

describe("reports", () => {
  it("ask the aggregate route for their groups, measure, order and limit", () => {
    expect(reportParams(feesByAuthor)).toEqual({ groupBy: "authorId", sum: "fee", sort: "sum_fee", limit: "5" })
    expect(reportParams(pagesOverTime).sort).toBe("pages")
    expect(reportParams(tickets.reports[0])).toEqual({ groupBy: "state", count: "*", sort: "-count", limit: "12" })
    expect(reportParams(tickets.reports[1])).toEqual({ sum: "views", limit: "12" })
  })

  it("turn groups into labelled numbers, decimals and missing keys included", () => {
    const points = reportPoints(feesByAuthor, books, {
      groups: [
        { key: { authorId: "a1" }, values: { sum_fee: "2.25" } },
        { key: { authorId: null }, values: { sum_fee: null } },
      ],
      hasMore: false,
    })
    expect(points).toEqual([{ label: "a1", value: 2.25 }, { label: "None", value: 0 }])
  })

  it("show a time series' latest buckets in time order", () => {
    expect(reportParams(ticketsPerMonth)).toEqual({ groupBy: "openedAt:month", "openedAt[null]": "false", count: "*", sort: "-openedAt", limit: "6" })
    expect(reportParams({ ...ticketsPerMonth, order: "valueDesc" }).sort).toBe("-count")
    const points = reportPoints(ticketsPerMonth, tickets, {
      groups: [
        { key: { openedAt: "2026-10-01T00:00:00Z" }, values: { count: 4 } },
        { key: { openedAt: "2026-09-01T00:00:00Z" }, values: { count: 2 } },
      ],
      hasMore: true,
    })
    expect(points.map((p) => p.value)).toEqual([2, 4])
    expect(points[1].label).toBe(formatBucket("2026-10-01T00:00:00Z", "month"))
    expect(describeReport(ticketsPerMonth)).toBe("Count by month")
  })

  it("fill the spans of a time series that have no records", () => {
    const groups = [
      { key: { openedAt: "2027-01-01T00:00:00Z" }, values: { count: 4, avg_views: "7.5" } },
      { key: { openedAt: "2026-10-01T00:00:00Z" }, values: { count: 2, avg_views: 3 } },
    ]
    const counts = reportPoints(ticketsPerMonth, tickets, { groups, hasMore: false })
    expect(counts.map((p) => p.value)).toEqual([2, 0, 0, 4])
    expect(counts.map((p) => p.label)).toEqual(["2026-10-01", "2026-11-01", "2026-12-01", "2027-01-01"].map((d) => formatBucket(`${d}T00:00:00Z`, "month")))
    const averages = reportPoints({ ...ticketsPerMonth, function: "avg", field: "views", measure: "avg_views" }, tickets, { groups, hasMore: false })
    expect(averages.map((p) => p.value)).toEqual([3, null, null, 7.5])
    // Daily buckets years apart would be too many points to fill, so only the ones with records are kept.
    const yearsApart = [{ key: { openedAt: "2020-01-01T00:00:00Z" }, values: { count: 1 } }, ...groups]
    const sparse = reportPoints({ ...ticketsPerMonth, bucket: "day" }, tickets, { groups: yearsApart, hasMore: false })
    expect(sparse.map((p) => p.value)).toEqual([1, 2, 4])
  })

  it("send their filters and time zone, and read buckets on that zone's calendar", () => {
    const manilaDays: AdminReport = {
      ...ticketsPerMonth, bucket: "day", timeZone: "Asia/Manila",
      filters: [{ field: "state", operator: "ne", values: ["closed"] }, { field: "views", operator: "in", values: ["1", "2"] }, { field: "subject", operator: "eq", values: ["Printer"] }],
    }
    expect(reportParams(manilaDays)).toEqual({
      "state[ne]": "closed", "views[in]": "1,2", subject: "Printer", count: "*", limit: "6", timeZone: "Asia/Manila",
      groupBy: "openedAt:day", "openedAt[null]": "false", sort: "-openedAt",
    })
    // Manila's days start at 16:00 UTC the day before, so these are the 16th and the 18th there.
    const points = reportPoints(manilaDays, tickets, {
      groups: [
        { key: { openedAt: "2026-02-17T16:00:00Z" }, values: { count: 3 } },
        { key: { openedAt: "2026-02-15T16:00:00Z" }, values: { count: 1 } },
      ],
      hasMore: false,
    })
    expect(points.map((p) => p.value)).toEqual([1, 0, 3])
    expect(points[0].label).toMatch(/16.*Feb.*2026|Feb.*16.*2026/)
    expect(points[2].label).toMatch(/18.*Feb.*2026|Feb.*18.*2026/)
  })

  it("turn a range into dates in the report's zone, and show every span of it", () => {
    // 10:00 on Thursday 22 October in Manila.
    const now = new Date("2026-10-22T02:00:00Z")
    const lastWeek: AdminReport = { ...ticketsPerMonth, bucket: "day", limit: 3, timeZone: "Asia/Manila", range: { field: "openedAt", last: 7, unit: "day" } }
    const params = reportParams(lastWeek, { now })
    expect(params["openedAt[gte]"]).toBe("2026-10-15T16:00:00.000Z")
    expect(params["openedAt[lt]"]).toBe("2026-10-22T16:00:00.000Z")
    // Seven days need seven groups, whatever the report's own limit.
    expect(params.limit).toBe("7")
    const points = reportPoints(lastWeek, tickets, { groups: [{ key: { openedAt: "2026-10-19T16:00:00Z" }, values: { count: 5 } }], hasMore: false }, { now })
    expect(points.map((p) => p.value)).toEqual([0, 0, 0, 0, 5, 0, 0])
    expect(points[4].label).toBe(formatBucket("2026-10-20T00:00:00Z", "day"))
    expect(describeReport(lastWeek, { now })).toBe("Count by day, last 7 days")

    // The viewer's choice replaces the report's own range: this month, in weeks from Monday.
    const chosen = { now, range: { last: 1, unit: "month" as const } }
    const weekly = { ...lastWeek, bucket: "week" as const }
    expect(reportParams(weekly, chosen)["openedAt[gte]"]).toBe("2026-09-30T16:00:00.000Z")
    expect(reportPoints(weekly, tickets, { groups: [], hasMore: false }, chosen).length).toBe(5)
    expect(describeReport(weekly, chosen)).toBe("Count by week, this month")
  })

  it("count a range across a daylight saving change by the zone's own midnights", () => {
    // New York moves to UTC-4 on 8 March 2026, so the window starts at UTC-5 and ends at UTC-4.
    const now = new Date("2026-03-10T15:00:00Z")
    const nearChange: AdminReport = { ...ticketsPerMonth, chart: "number", groupBy: null, bucket: null, timeZone: "America/New_York", range: { field: "openedAt", last: 3, unit: "day" } }
    const params = reportParams(nearChange, { now })
    expect(params["openedAt[gte]"]).toBe("2026-03-08T05:00:00.000Z")
    expect(params["openedAt[lt]"]).toBe("2026-03-11T04:00:00.000Z")
    expect([describeRange({ last: 1, unit: "day" }), describeRange({ last: 12, unit: "month" })]).toEqual(["today", "last 12 months"])
  })

  it("label buckets in UTC", () => {
    // Midnight UTC is still the 31st in timezones behind it; the label must not slip a day or a year.
    expect(formatBucket("2027-01-01T00:00:00Z", "year")).toBe("2027")
    expect(formatBucket("2026-12-28T00:00:00Z", "week")).toMatch(/^Week of .*28.*2026$/)
    expect(formatBucket("2026-02-01T00:00:00Z", "month")).toMatch(/Feb.*2026/)
    expect(formatBucket("not a date", "day")).toBe("not a date")
  })

  it("say what they show", () => {
    expect(describeReport(feesByAuthor)).toBe("Sum of fee by author")
    expect(describeReport(tickets.reports[0])).toBe("Count by state")
    expect(describeReport(tickets.reports[1])).toBe("Sum of views")
  })
})
