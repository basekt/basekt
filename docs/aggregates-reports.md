# Aggregates and reports

## Aggregates

**Aggregates** compute grouped totals in the data source instead of loading every record: stock on hand from a
ledger of movements, totals per site, or a dashboard's numbers.

```
GET /api/aggregate/movements?groupBy=itemId,siteId&sum=quantity&count&max=at&quantity[gt]=0&sort=-sum_quantity&limit=50
→ {"groups": [{"key": {"itemId": "i1", "siteId": "north"}, "values": {"sum_quantity": 7, "count": 2, "max_at": "…"}}],
   "hasMore": false}
```

The parameters are:

- `groupBy`, with one or more fields. An instant field can be grouped by `day`, `week` (from Monday), `month` or
  `year`, as in `groupBy=at:month`, and its key is the start of each span. Spans follow UTC's calendar, or a time zone
  region's with `timeZone=Asia/Manila`;
- `count` (records, or `count=*`), `count=field` (records with the field set), and `sum=`, `avg=`, `min=` and `max=`,
  each with one or more fields;
- `sort`, by group fields or measure names, and `limit`, up to `basekt.aggregate.maxLimit` (1000 by default);
- the usual filters.

Sums keep integers as integers. An average is exact to 12 places on every database. The caller's read
[scope](permissions.md#record-scopes) applies, so staff see only their own sites' totals.

It needs a data source that offers `Aggregating`, as the in-memory and Exposed adapters do (Exposed runs one
`SELECT … GROUP BY`). Otherwise it's `501 not_implemented`. The Exposed adapter groups by time spans on H2 and
Postgres; on other databases a `groupBy` with one is refused as `invalid_query`. On H2, a span in a zone with daylight
saving splits on the day the clocks change.

The [admin API](admin-api.md) serves the same at `GET /admin/api/resources/{name}/aggregate`, with `search` too.

## Reports

**Reports** are charts of those aggregates, declared on a resource as schema data. The
[admin panel](admin-panel.md) draws them on its home screen:

```kotlin
resource("orders") {
    enum("status", listOf("placed", "paid", "shipped"))
    decimal("total")
    instant("placedAt")
    report("orderCount", Chart.NUMBER)                                  // a count of every order
    report("revenueByStatus") { groupBy("status"); sum("total") }        // a bar per status, largest first
    report("ordersByStatus", Chart.PIE) { groupBy("status"); limit(5) }
    report("revenuePerDay", Chart.LINE) {                               // a time series
        groupBy("placedAt", TimeBucket.DAY, timeZone = "Asia/Manila")   // days of Manila's calendar
        sum("total")
        where("status", ReportOperator.NE, "cancelled")
        range("placedAt", 30, TimeBucket.DAY)                           // today and the 29 days before
        compare(ReportCompare.PREVIOUS_PERIOD)                          // beside the 30 days before them
    }
}
```

A report is a `number`, `bar`, `line` or `pie`. It counts records, or takes the `sum`, `avg`, `min` or `max` of a
number field, for each value of its group field. Bars and pies show the largest groups first, and lines go in group
order. `order(...)` changes that, and `limit(...)` caps the groups.

### Time series and time zones

A line grouped by a time span is a time series: it shows the latest spans and leaves out records without a time.
Spans follow UTC's calendar unless the report names a time zone region.

### Filters

`where(...)` counts only the records that match, with the operators of the list filters (`eq`, `ne`, `gt`, `gte`,
`lt`, `lte`, `in`, `contains` and `null`).

### Rolling ranges

`range(field, last, unit)` is a rolling window: the records whose instant falls in the last spans up to now, the
current one included, such as the last 14 days or this month. It is turned into dates each time the report is drawn,
so it never goes stale, and a time series over it shows every span of the window, empty ones too.

The panel picks ranges with a date range picker: presets (today, yesterday, this or last week, the last 7 or 30 days,
this or last month, this or last year), the first and last day as date fields, and a two-month calendar whose weeks
start on Monday, like the week buckets. Picked days count on the report's time zone calendar, both included. The one
on the home screen replaces the range of every report that has one. Each card with a range has its own, which wins
over it and adds a "Compare to" switch: the same period last year, or the previous period of the same length. The
browser remembers each choice.

### Comparisons

`compare(...)` makes a comparison the report's default, with `ReportCompare.PREVIOUS_PERIOD` or
`ReportCompare.PREVIOUS_YEAR`. A comparison runs the aggregate a second time for the earlier window: a line draws it
dashed, span by span; bars pair up by group; and a number shows how it moved, such as "↑ 12% vs previous period
(1,204)". A pie can't be compared.

### Access and colours

A report gives no new access: the caller needs read access to the resource, its scope applies to the numbers, and the
panel fetches them from the aggregate route. The panel draws them with shadcn/ui's charts, in the theme's `chart-1`
to `chart-5` colours.
