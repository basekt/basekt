import { Bar, BarChart, CartesianGrid, Cell, Line, LineChart, Pie, PieChart, XAxis, YAxis } from "recharts"
import type { AdminReport, AdminResource, ReportCompare } from "@wire"
import { ErrorState } from "@/components/error-state"
import { ReportRangeMenu, useCardChoice } from "@/components/report-range-menu"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { ChartContainer, ChartLegend, ChartLegendContent, ChartTooltip, ChartTooltipContent, type ChartConfig } from "@/components/ui/chart"
import { Skeleton } from "@/components/ui/skeleton"
import { humanize } from "@/lib/fields"
import { compareOf, describeCompare, describeReport, reportParams, reportPoints, type RollingRange, type ReportPoint, type ReportView } from "@/lib/reports"
import { useAsync } from "@/lib/use-async"
import { usePanel } from "@/panel"

/** The theme's chart colours, which pie slices take in turn. */
const colors = [1, 2, 3, 4, 5].map((n) => `var(--chart-${n})`)

/**
 * A report of [resource] as a card: its title, what it shows, and its chart, loaded from the aggregate route. [range],
 * the home screen's, replaces the report's own range, and the card's menu can replace both and add a comparison,
 * which loads the earlier window too.
 */
export function ReportCard({ resource, report, range = null }: { resource: AdminResource; report: AdminReport; range?: RollingRange | null }) {
  const { api } = usePanel()
  const [choice, setChoice] = useCardChoice(resource.name, report.name)
  const view: ReportView = { range: choice.range ?? range, compare: choice.compare }
  const compare = compareOf(report, view)
  const { data, error, loading, reload } = useAsync(
    async () => {
      const at = { ...view, now: new Date() }
      const [page, previous] = await Promise.all([
        api.aggregate(resource.name, reportParams(report, at)),
        compare ? api.aggregate(resource.name, reportParams(report, at, "previous")) : undefined,
      ])
      return reportPoints(report, resource, page, at, previous)
    },
    [api, resource, report, JSON.stringify(view.range), compare],
  )
  const title = humanize(report.name)
  return (
    <Card className={report.chart === "number" ? "self-start" : "sm:col-span-2"} role="figure" aria-label={title}>
      <CardHeader>
        <CardDescription>{humanize(resource.name)}</CardDescription>
        <CardTitle>{title}</CardTitle>
        {report.range && (
          <CardAction>
            <ReportRangeMenu report={report} title={title} choice={choice} onChange={setChoice} />
          </CardAction>
        )}
      </CardHeader>
      <CardContent>
        {error ? (
          <ErrorState error={error} onRetry={reload} />
        ) : loading || !data ? (
          <Skeleton className={report.chart === "number" ? "h-9 w-24" : "aspect-video w-full"} />
        ) : (
          <ReportChart report={report} points={data} compare={compare} />
        )}
        <p className="mt-2 text-sm text-muted-foreground">{describeReport(report, view)}</p>
      </CardContent>
    </Card>
  )
}

function ReportChart({ report, points, compare }: { report: AdminReport; points: ReportPoint[]; compare: ReportCompare | null }) {
  if (report.chart === "number") {
    const value = points[0]?.value ?? 0
    return (
      <div>
        <p className="text-3xl font-semibold tabular-nums">{value.toLocaleString()}</p>
        {compare && <Change value={value} previous={points[0]?.previous ?? null} compare={compare} />}
      </div>
    )
  }
  if (points.length === 0) return <p className="py-8 text-center text-sm text-muted-foreground">No records yet.</p>
  const config: ChartConfig = {
    value: { label: compare ? "Now" : describeReport(report), color: colors[0] },
    ...(compare ? { previous: { label: capitalize(describeCompare(compare)), color: colors[1] } } : {}),
  }
  switch (report.chart) {
    case "line":
      return (
        <ChartContainer config={config}>
          <LineChart data={points} accessibilityLayer>
            <CartesianGrid vertical={false} />
            <XAxis dataKey="label" tickLine={false} axisLine={false} />
            <YAxis tickLine={false} axisLine={false} width={40} />
            <ChartTooltip content={<ChartTooltipContent />} />
            <Line dataKey="value" type="linear" stroke="var(--color-value)" strokeWidth={2} dot={false} />
            {compare && <Line dataKey="previous" type="linear" stroke="var(--color-previous)" strokeWidth={2} strokeDasharray="4 4" dot={false} />}
            {compare && <ChartLegend content={<ChartLegendContent />} />}
          </LineChart>
        </ChartContainer>
      )
    case "pie": {
      const slices: ChartConfig = Object.fromEntries(points.map((p, i) => [p.label, { label: p.label, color: colors[i % colors.length] }]))
      return (
        <ChartContainer config={slices} className="mx-auto max-h-72">
          <PieChart accessibilityLayer>
            <ChartTooltip content={<ChartTooltipContent nameKey="label" hideLabel />} />
            <Pie data={points} dataKey="value" nameKey="label" innerRadius="50%">
              {points.map((p, i) => <Cell key={p.label} fill={colors[i % colors.length]} />)}
            </Pie>
            <ChartLegend content={<ChartLegendContent nameKey="label" />} />
          </PieChart>
        </ChartContainer>
      )
    }
    default:
      return (
        <ChartContainer config={config}>
          <BarChart data={points} accessibilityLayer>
            <CartesianGrid vertical={false} />
            <XAxis dataKey="label" tickLine={false} axisLine={false} />
            <YAxis tickLine={false} axisLine={false} width={40} />
            <ChartTooltip content={<ChartTooltipContent />} />
            <Bar dataKey="value" fill="var(--color-value)" radius={4} />
            {compare && <Bar dataKey="previous" fill="var(--color-previous)" radius={4} />}
            {compare && <ChartLegend content={<ChartLegendContent />} />}
          </BarChart>
        </ChartContainer>
      )
  }
}

/** How a number moved since the earlier window: "↑ 12% vs previous period (1,234)". */
function Change({ value, previous, compare }: { value: number; previous: number | null; compare: ReportCompare }) {
  const before = previous ?? 0
  const percent = before === 0 ? null : Math.round(((value - before) / Math.abs(before)) * 100)
  const arrow = value > before ? "↑" : value < before ? "↓" : "→"
  return (
    <p className="mt-1 text-sm text-muted-foreground">
      <span className="font-medium text-foreground">{arrow} {percent === null ? "—" : `${Math.abs(percent)}%`}</span> vs{" "}
      {describeCompare(compare)} ({before.toLocaleString()})
    </p>
  )
}

function capitalize(text: string): string {
  return text[0].toUpperCase() + text.slice(1)
}
