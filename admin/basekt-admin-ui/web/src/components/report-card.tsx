import { Bar, BarChart, CartesianGrid, Cell, Line, LineChart, Pie, PieChart, XAxis, YAxis } from "recharts"
import type { AdminReport, AdminResource } from "@wire"
import { ErrorState } from "@/components/error-state"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { ChartContainer, ChartLegend, ChartLegendContent, ChartTooltip, ChartTooltipContent, type ChartConfig } from "@/components/ui/chart"
import { Skeleton } from "@/components/ui/skeleton"
import { humanize } from "@/lib/fields"
import { describeReport, reportParams, reportPoints, type RangeChoice, type ReportPoint } from "@/lib/reports"
import { useAsync } from "@/lib/use-async"
import { usePanel } from "@/panel"

/** The theme's chart colours, which pie slices take in turn. */
const colors = [1, 2, 3, 4, 5].map((n) => `var(--chart-${n})`)

/**
 * A report of [resource] as a card: its title, what it shows, and its chart, loaded from the aggregate route. [range]
 * replaces the report's own range, if it has one.
 */
export function ReportCard({ resource, report, range = null }: { resource: AdminResource; report: AdminReport; range?: RangeChoice | null }) {
  const { api } = usePanel()
  const { data, error, loading, reload } = useAsync(
    () => {
      const view = { range, now: new Date() }
      return api.aggregate(resource.name, reportParams(report, view)).then((page) => reportPoints(report, resource, page, view))
    },
    [api, resource, report, range?.last, range?.unit],
  )
  return (
    <Card className={report.chart === "number" ? "self-start" : "sm:col-span-2"} role="figure" aria-label={humanize(report.name)}>
      <CardHeader>
        <CardDescription>{humanize(resource.name)}</CardDescription>
        <CardTitle>{humanize(report.name)}</CardTitle>
      </CardHeader>
      <CardContent>
        {error ? (
          <ErrorState error={error} onRetry={reload} />
        ) : loading || !data ? (
          <Skeleton className={report.chart === "number" ? "h-9 w-24" : "aspect-video w-full"} />
        ) : (
          <ReportChart report={report} points={data} />
        )}
        <p className="mt-2 text-sm text-muted-foreground">{describeReport(report, { range })}</p>
      </CardContent>
    </Card>
  )
}

function ReportChart({ report, points }: { report: AdminReport; points: ReportPoint[] }) {
  if (report.chart === "number") {
    return <p className="text-3xl font-semibold tabular-nums">{(points[0]?.value ?? 0).toLocaleString()}</p>
  }
  if (points.length === 0) return <p className="py-8 text-center text-sm text-muted-foreground">No records yet.</p>
  const config: ChartConfig = { value: { label: describeReport(report), color: colors[0] } }
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
          </BarChart>
        </ChartContainer>
      )
  }
}
