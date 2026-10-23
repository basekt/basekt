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
 * The aggregate parameters that compute [report]: its filters, groups, measure, their order and how many. A time
 * series in group order asks for its latest buckets, which [reportPoints] puts back in time order, and leaves out
 * records without a time.
 */
export function reportParams(report: AdminReport): ListParams {
  const params: ListParams = {}
  for (const filter of report.filters) {
    params[filter.operator === "eq" ? filter.field : `${filter.field}[${filter.operator}]`] = filter.values.join(",")
  }
  params[report.function] = report.field ?? "*"
  params.limit = String(report.limit)
  if (report.timeZone) params.timeZone = report.timeZone
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
    const label =
      key === null || key === undefined ? "None" : report.bucket ? formatBucket(String(key), report.bucket, zoneOf(report)) : formatValue(field, key)
    return { label, value: toNumber(group.values[report.measure]) }
  })
}

/**
 * The buckets of [page], oldest first, with the spans between them that have no records filled in: as 0 for a count
 * or a sum, and as `null` for an average, min or max, which have no value there.
 */
function timeSeries(report: AdminReport, bucket: TimeBucket, page: AdminAggregatePage): ReportPoint[] {
  // Keyed by calendar date, so the steps between buckets are whole days and months whatever the zone's offsets do.
  const values = new Map<number, number>()
  for (const group of page.groups) {
    const time = calendarDay(String(group.key[report.groupBy!]), zoneOf(report))
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

/**
 * A bucket's start as a label on [zone]'s calendar, where the bucket was cut: "16 Feb 2026", "Week of 16 Feb 2026",
 * "Feb 2026" or "2026".
 */
export function formatBucket(start: string, bucket: TimeBucket, zone = "UTC"): string {
  const day = calendarDay(start, zone)
  if (Number.isNaN(day)) return start
  const date = new Date(day)
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

/**
 * The date [instant] falls on in [zone], as the UTC midnight of that date: a calendar day with no offset left in it,
 * or NaN when [instant] isn't one. An unknown zone counts as UTC.
 */
function calendarDay(instant: string, zone: string): number {
  const date = new Date(instant)
  if (Number.isNaN(date.getTime())) return NaN
  let parts: Intl.DateTimeFormatPart[]
  try {
    parts = new Intl.DateTimeFormat("en-US", { timeZone: zone, year: "numeric", month: "numeric", day: "numeric" }).formatToParts(date)
  } catch {
    return Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate())
  }
  const part = (type: string) => Number(parts.find((p) => p.type === type)?.value)
  return Date.UTC(part("year"), part("month") - 1, part("day"))
}

/** The zone whose calendar [report]'s buckets follow. */
function zoneOf(report: AdminReport): string {
  return report.timeZone ?? "UTC"
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
