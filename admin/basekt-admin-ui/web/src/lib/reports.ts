import type { AdminAggregatePage, AdminReport, AdminResource, JsonValue, ReportCompare, TimeBucket } from "@wire"
import type { ListParams } from "@/api/client"
import { formatValue, humanize } from "@/lib/fields"

/**
 * One group of a report, ready to plot. A `null` value is a time span without records, which a line skips. When the
 * report is compared, [previous] is the same group's value in the earlier window.
 */
export interface ReportPoint {
  label: string
  value: number | null
  previous?: number | null
}

/** The most points a time series is filled out to; a sparser one keeps only the spans that have records. */
const MAX_FILLED_POINTS = 400

/** The most groups the aggregate route returns by default, which caps how many spans a range's series asks for. */
const MAX_GROUPS = 1000

/** A rolling window chosen in place of a report's own: the [last] spans of [unit] up to now. */
export interface RollingRange {
  last: number
  unit: TimeBucket
}

/** Dates chosen in place of a report's range: [from] to [to], both included, as `YYYY-MM-DD` on the report's calendar. */
export interface CustomRange {
  from: string
  to: string
}

/** A window chosen in place of a report's own range. */
export type RangeChoice = RollingRange | CustomRange

/**
 * How a report is drawn this time: the range the viewer chose in place of the report's, if any; the comparison they
 * chose, where `null` turns the report's own off and `undefined` keeps it; and the moment the ranges count back from.
 * One view serves [reportParams] and [reportPoints], so they agree on the windows.
 */
export interface ReportView {
  range?: RangeChoice | null
  compare?: ReportCompare | null
  now?: Date
}

/**
 * The window a report's records must fall in, as calendar days (UTC midnights) [from] inclusive and [to] exclusive,
 * and the [step] that moves it back by its own length.
 */
interface Window {
  field: string
  from: number
  to: number
  step: { unit: TimeBucket; count: number }
}

const DAY_MS = 86_400_000

/**
 * The aggregate parameters that compute [report]: its filters and range, groups, measure, their order and how many. A
 * time series in group order asks for its latest buckets, which [reportPoints] puts back in time order, and leaves
 * out records without a time; over its range, it asks for every span of the window. The `previous` [period] is the
 * window the report is compared with, as [compareOf] says.
 */
export function reportParams(report: AdminReport, view: ReportView = {}, period: "current" | "previous" = "current"): ListParams {
  const params: ListParams = {}
  for (const filter of report.filters) {
    params[filter.operator === "eq" ? filter.field : `${filter.field}[${filter.operator}]`] = filter.values.join(",")
  }
  params[report.function] = report.field ?? "*"
  params.limit = String(report.limit)
  if (report.timeZone) params.timeZone = report.timeZone
  const window = period === "previous" ? previousWindow(report, view) : windowOf(report, view)
  if (window) {
    params[`${window.field}[gte]`] = new Date(zonedMidnight(window.from, zoneOf(report))).toISOString()
    params[`${window.field}[lt]`] = new Date(zonedMidnight(window.to, zoneOf(report))).toISOString()
    if (report.bucket && report.groupBy === window.field) {
      params.limit = String(Math.min(MAX_GROUPS, Math.max(report.limit, spansOf(window, report.bucket).length)))
    }
  }
  if (report.groupBy === null) return params
  params.groupBy = report.bucket ? `${report.groupBy}:${report.bucket}` : report.groupBy
  if (report.bucket) params[`${report.groupBy}[null]`] = "false"
  params.sort =
    report.order === "group" ? `${latestFirst(report) ? "-" : ""}${report.groupBy}` : report.order === "valueAsc" ? report.measure : `-${report.measure}`
  return params
}

/**
 * The groups of [page] as labelled numbers. A group without a value counts as 0, and one without a key as "None".
 * With [previous], the page of the earlier window, each point also holds its [ReportPoint.previous] value: the span in
 * the same place for a time series, the group with the same label otherwise.
 */
export function reportPoints(
  report: AdminReport,
  resource: AdminResource,
  page: AdminAggregatePage,
  view: ReportView = {},
  previous?: AdminAggregatePage,
): ReportPoint[] {
  const current = pointsOf(report, resource, page, windowOf(report, view))
  if (!previous || !compareOf(report, view)) return current
  const before = pointsOf(report, resource, previous, previousWindow(report, view))
  if (report.bucket && latestFirst(report)) return current.map((point, i) => ({ ...point, previous: before[i]?.value ?? null }))
  const byLabel = new Map(before.map((point) => [point.label, point.value]))
  return current.map((point) => ({ ...point, previous: byLabel.get(point.label) ?? emptyValue(report) }))
}

/** The earlier window [report] is compared with this time: the viewer's choice, or the report's own. Null without a range. */
export function compareOf(report: AdminReport, view: ReportView = {}): ReportCompare | null {
  if (!report.range) return null
  return view.compare === undefined ? report.compare : view.compare
}

function pointsOf(report: AdminReport, resource: AdminResource, page: AdminAggregatePage, window: Window | null): ReportPoint[] {
  if (report.bucket && latestFirst(report)) {
    return timeSeries(report, report.bucket, page, window?.field === report.groupBy ? window : null)
  }
  const field = resource.fields.find((f) => f.name === report.groupBy)
  return page.groups.map((group) => {
    const key = report.groupBy === null ? null : group.key[report.groupBy]
    const label =
      key === null || key === undefined ? "None" : report.bucket ? formatBucket(String(key), report.bucket, zoneOf(report)) : formatValue(field, key)
    return { label, value: toNumber(group.values[report.measure]) }
  })
}

/**
 * The buckets of [page], oldest first, with the spans that have no records filled in: as 0 for a count or a sum, and
 * as `null` for an average, min or max, which have no value there. Over a [window], every span of it is shown;
 * otherwise the spans from the first bucket with records to the last.
 */
function timeSeries(report: AdminReport, bucket: TimeBucket, page: AdminAggregatePage, window: Window | null): ReportPoint[] {
  // Keyed by calendar date, so the steps between buckets are whole days and months whatever the zone's offsets do.
  const values = new Map<number, number>()
  for (const group of page.groups) {
    const time = calendarDay(String(group.key[report.groupBy!]), zoneOf(report))
    if (!Number.isNaN(time)) values.set(time, toNumber(group.values[report.measure]))
  }
  const times = [...values.keys()].sort((a, b) => a - b)
  const empty = emptyValue(report)
  let spans: number[]
  if (window) {
    spans = spansOf(window, bucket)
  } else {
    if (times.length === 0) return []
    const filled: number[] = []
    for (let time = times[0]; time <= times[times.length - 1] && filled.length <= MAX_FILLED_POINTS; time = addSpans(time, bucket, 1)) filled.push(time)
    spans = filled.length > MAX_FILLED_POINTS ? times : filled
  }
  return spans.map((time) => ({ label: formatBucket(new Date(time).toISOString(), bucket), value: values.get(time) ?? empty }))
}

/** What a span or group without records is worth: 0 for a count or a sum, nothing for an average, min or max. */
function emptyValue(report: AdminReport): number | null {
  return report.function === "count" || report.function === "sum" ? 0 : null
}

/** The window [report] counts records in this time, from the viewer's choice or its own range, or null for all time. */
function windowOf(report: AdminReport, view: ReportView): Window | null {
  if (!report.range) return null
  const choice = view.range ?? report.range
  if ("from" in choice) {
    const [first, last] = [Date.parse(`${choice.from}T00:00:00Z`), Date.parse(`${choice.to}T00:00:00Z`)].sort((a, b) => a - b)
    const to = addSpans(last, "day", 1)
    return { field: report.range.field, from: first, to, step: { unit: "day", count: Math.round((to - first) / DAY_MS) } }
  }
  const { last, unit } = choice
  const today = calendarDay((view.now ?? new Date()).toISOString(), zoneOf(report))
  const current = startOfSpan(today, unit)
  return { field: report.range.field, from: addSpans(current, unit, 1 - last), to: addSpans(current, unit, 1), step: { unit, count: last } }
}

/**
 * The window [report] is compared with: the one just before of the same length (the same number of days, or of whole
 * months for a range in months), or the same dates a year earlier. Null when it isn't compared.
 */
function previousWindow(report: AdminReport, view: ReportView): Window | null {
  const window = windowOf(report, view)
  const mode = compareOf(report, view)
  if (!window || !mode) return null
  if (mode === "previousYear") return { ...window, from: addSpans(window.from, "year", -1), to: addSpans(window.to, "year", -1) }
  return { ...window, from: addSpans(window.from, window.step.unit, -window.step.count), to: window.from }
}

/** The starts of the [bucket]s that overlap [window], oldest first, as calendar days. */
function spansOf(window: Window, bucket: TimeBucket): number[] {
  const spans: number[] = []
  for (let time = startOfSpan(window.from, bucket); time < window.to && spans.length < MAX_GROUPS; time = addSpans(time, bucket, 1)) spans.push(time)
  return spans
}

/** The calendar day that starts the [unit] holding the calendar day [day]: weeks start on Monday. */
function startOfSpan(day: number, unit: TimeBucket): number {
  const date = new Date(day)
  switch (unit) {
    case "day":
      return day
    case "week":
      return Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate() - ((date.getUTCDay() + 6) % 7))
    case "month":
      return Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), 1)
    case "year":
      return Date.UTC(date.getUTCFullYear(), 0, 1)
  }
}

/** The calendar day [count] spans of [unit] after [day], or before it when [count] is negative. */
function addSpans(day: number, unit: TimeBucket, count: number): number {
  const date = new Date(day)
  switch (unit) {
    case "day":
      return date.setUTCDate(date.getUTCDate() + count)
    case "week":
      return date.setUTCDate(date.getUTCDate() + 7 * count)
    case "month":
      return date.setUTCMonth(date.getUTCMonth() + count)
    case "year":
      return date.setUTCFullYear(date.getUTCFullYear() + count)
  }
}

/**
 * The instant midnight of the calendar day [day] begins in [zone]: [day] less the zone's offset at that moment, found
 * in two steps because the offset depends on the instant being found. An unknown zone counts as UTC.
 */
function zonedMidnight(day: number, zone: string): number {
  const offsetAt = (instant: number) => wallClock(instant, zone) - instant
  return day - offsetAt(day - offsetAt(day))
}

/** The time a clock in [zone] shows at [instant], read as if it were UTC. */
function wallClock(instant: number, zone: string): number {
  let parts: Intl.DateTimeFormatPart[]
  try {
    parts = new Intl.DateTimeFormat("en-US", {
      timeZone: zone, hourCycle: "h23", year: "numeric", month: "numeric", day: "numeric", hour: "numeric", minute: "numeric", second: "numeric",
    }).formatToParts(new Date(instant))
  } catch {
    return instant
  }
  const part = (type: string) => Number(parts.find((p) => p.type === type)?.value)
  return Date.UTC(part("year"), part("month") - 1, part("day"), part("hour"), part("minute"), part("second"))
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

/**
 * What [report] shows, such as "Sum of minutes by dog", "Count" or "Sum of total by day, last 14 days, vs previous
 * period".
 */
export function describeReport(report: AdminReport, view: ReportView = {}): string {
  const what = report.field === null ? "Count" : `${humanize(report.function)} of ${humanize(report.field).toLowerCase()}`
  const grouped = report.groupBy === null ? what : `${what} by ${report.bucket ?? humanize(report.groupBy).toLowerCase()}`
  const range = report.range ? (view.range ?? report.range) : null
  if (!range) return grouped
  const mode = compareOf(report, view)
  return `${grouped}, ${describeRange(range)}${mode ? ` vs ${describeCompare(mode)}` : ""}`
}

/** A range in words: "today", "this month", "last 14 days", "last 3 months" or "1 Oct 2026 – 15 Oct 2026". */
export function describeRange(choice: RangeChoice): string {
  if ("from" in choice) return `${formatBucket(`${choice.from}T00:00:00Z`, "day")} – ${formatBucket(`${choice.to}T00:00:00Z`, "day")}`
  const { last, unit } = choice
  if (last === 1) return unit === "day" ? "today" : `this ${unit}`
  return `last ${last} ${unit}s`
}

/** A comparison in words: "previous period" or "same period last year". */
export function describeCompare(mode: ReportCompare): string {
  return mode === "previousYear" ? "same period last year" : "previous period"
}

/** A measure as a number: decimals travel as strings. */
function toNumber(value: JsonValue | undefined): number {
  const number = typeof value === "number" ? value : Number(value ?? 0)
  return Number.isFinite(number) ? number : 0
}
