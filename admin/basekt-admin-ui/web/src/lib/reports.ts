import type { AdminAggregatePage, AdminReport, AdminResource, JsonValue, TimeBucket } from "@wire"
import type { ListParams } from "@/api/client"
import { formatValue, humanize } from "@/lib/fields"

/** One group of a report, ready to plot. A `null` value is a time span without records, which a line skips. */
export interface ReportPoint {
  label: string
  value: number | null
}

/** The most points a time series is filled out to; a sparser one keeps only the spans that have records. */
const MAX_FILLED_POINTS = 400

/**
 * The aggregate parameters that compute [report]: its groups, its measure, their order and how many. A time series in
 * group order asks for its latest buckets, which [reportPoints] puts back in time order, and leaves out records
 * without a time.
 */
export function reportParams(report: AdminReport): ListParams {
  const params: ListParams = { [report.function]: report.field ?? "*", limit: String(report.limit) }
  if (report.groupBy === null) return params
  params.groupBy = report.bucket ? `${report.groupBy}:${report.bucket}` : report.groupBy
  if (report.bucket) params[`${report.groupBy}[null]`] = "false"
  params.sort =
    report.order === "group" ? `${latestFirst(report) ? "-" : ""}${report.groupBy}` : report.order === "valueAsc" ? report.measure : `-${report.measure}`
  return params
}

/** The groups of [page] as labelled numbers. A group without a value counts as 0, and one without a key as "None". */
export function reportPoints(report: AdminReport, resource: AdminResource, page: AdminAggregatePage): ReportPoint[] {
  if (report.bucket && latestFirst(report)) return timeSeries(report, report.bucket, page)
  const field = resource.fields.find((f) => f.name === report.groupBy)
  return page.groups.map((group) => {
    const key = report.groupBy === null ? null : group.key[report.groupBy]
    const label = key === null || key === undefined ? "None" : report.bucket ? formatBucket(String(key), report.bucket) : formatValue(field, key)
    return { label, value: toNumber(group.values[report.measure]) }
  })
}

/**
 * The buckets of [page], oldest first, with the spans between them that have no records filled in: as 0 for a count
 * or a sum, and as `null` for an average, min or max, which have no value there.
 */
function timeSeries(report: AdminReport, bucket: TimeBucket, page: AdminAggregatePage): ReportPoint[] {
  const values = new Map<number, number>()
  for (const group of page.groups) {
    const time = Date.parse(String(group.key[report.groupBy!]))
    if (!Number.isNaN(time)) values.set(time, toNumber(group.values[report.measure]))
  }
  const times = [...values.keys()].sort((a, b) => a - b)
  if (times.length === 0) return []
  const empty = report.function === "count" || report.function === "sum" ? 0 : null
  const filled: number[] = []
  for (let time = times[0]; time <= times[times.length - 1] && filled.length <= MAX_FILLED_POINTS; time = nextBucket(time, bucket)) filled.push(time)
  const spans = filled.length > MAX_FILLED_POINTS ? times : filled
  return spans.map((time) => ({ label: formatBucket(new Date(time).toISOString(), bucket), value: values.get(time) ?? empty }))
}

/** The start of the bucket after the one starting at [time], in UTC. */
function nextBucket(time: number, bucket: TimeBucket): number {
  const date = new Date(time)
  switch (bucket) {
    case "day":
      return date.setUTCDate(date.getUTCDate() + 1)
    case "week":
      return date.setUTCDate(date.getUTCDate() + 7)
    case "month":
      return date.setUTCMonth(date.getUTCMonth() + 1)
    case "year":
      return date.setUTCFullYear(date.getUTCFullYear() + 1)
  }
}

/** A bucket's start as a label: "16 Feb 2026", "Week of 16 Feb 2026", "Feb 2026" or "2026", in UTC like the bucket. */
export function formatBucket(start: string, bucket: TimeBucket): string {
  const date = new Date(start)
  if (Number.isNaN(date.getTime())) return start
  switch (bucket) {
    case "year":
      return String(date.getUTCFullYear())
    case "month":
      return date.toLocaleDateString(undefined, { timeZone: "UTC", month: "short", year: "numeric" })
    case "week":
      return `Week of ${date.toLocaleDateString(undefined, { timeZone: "UTC", day: "numeric", month: "short", year: "numeric" })}`
    default:
      return date.toLocaleDateString(undefined, { timeZone: "UTC", day: "numeric", month: "short", year: "numeric" })
  }
}

/** Whether [report] is a time series in time order, which shows its latest buckets. */
function latestFirst(report: AdminReport): boolean {
  return report.bucket != null && report.order === "group"
}

/** What [report] shows, such as "Sum of minutes by dog" or "Count". */
export function describeReport(report: AdminReport): string {
  const what = report.field === null ? "Count" : `${humanize(report.function)} of ${humanize(report.field).toLowerCase()}`
  if (report.groupBy === null) return what
  return `${what} by ${report.bucket ?? humanize(report.groupBy).toLowerCase()}`
}

/** A measure as a number: decimals travel as strings. */
function toNumber(value: JsonValue | undefined): number {
  const number = typeof value === "number" ? value : Number(value ?? 0)
  return Number.isFinite(number) ? number : 0
}
