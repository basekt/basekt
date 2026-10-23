import { CalendarRange } from "lucide-react"
import { useCallback, useState } from "react"
import type { DateRange } from "react-day-picker"
import type { ReportCompare } from "@wire"
import { Button } from "@/components/ui/button"
import { Calendar } from "@/components/ui/calendar"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { Switch } from "@/components/ui/switch"
import { describeCompare, describeRange, rangeDates, type RangeChoice, type RollingRange } from "@/lib/reports"

/** The ready-made windows of the picker, in the order it lists them. */
export const presets: { label: string; range: RollingRange }[] = [
  { label: "Today", range: { last: 1, unit: "day" } },
  { label: "Yesterday", range: { last: 1, unit: "day", skip: 1 } },
  { label: "This week", range: { last: 1, unit: "week" } },
  { label: "Last week", range: { last: 1, unit: "week", skip: 1 } },
  { label: "Last 7 days", range: { last: 7, unit: "day" } },
  { label: "Last 30 days", range: { last: 30, unit: "day" } },
  { label: "This month", range: { last: 1, unit: "month" } },
  { label: "Last month", range: { last: 1, unit: "month", skip: 1 } },
  { label: "This year", range: { last: 1, unit: "year" } },
  { label: "Last year", range: { last: 1, unit: "year", skip: 1 } },
]

/** The comparison a switched-on toggle starts with. */
const firstCompare: ReportCompare = "previousYear"

/**
 * A date range picker: a compare toggle when [compare] is given, the first and last day as date fields, the presets
 * on the side, and a two-month calendar whose weeks start on Monday, like the reports' week buckets. [value] is the
 * chosen range, or `null` for the default, whose dates are [defaultRange]'s when there is one. Dates are days of
 * [zone]'s calendar. [label] names the trigger; [compact] shows it as an icon.
 */
export function DateRangePicker({ value, defaultRange = null, onChange, compare, zone = "UTC", label, compact = false }: {
  value: RangeChoice | null
  defaultRange?: RangeChoice | null
  onChange: (value: RangeChoice | null) => void
  compare?: { value: ReportCompare | null; onChange: (value: ReportCompare | null) => void }
  zone?: string
  label: string
  compact?: boolean
}) {
  const shown = value ?? defaultRange
  const dates = shown ? rangeDates(shown, zone) : null
  // The first click on the calendar starts a range; it is chosen once the second click ends it.
  const [pending, setPending] = useState<DateRange | undefined>()
  const selected = pending ?? (dates ? { from: fromYmd(dates.from), to: fromYmd(dates.to) } : undefined)
  const chooseDates = (from: string, to: string) => {
    setPending(undefined)
    if (from && to) onChange({ from, to })
  }
  return (
    <Popover onOpenChange={() => setPending(undefined)}>
      <PopoverTrigger asChild>
        <Button variant={compact ? "ghost" : "outline"} size="sm" aria-label={label}>
          <CalendarRange />
          {!compact && <span>{value ? capitalize(describeRange(value)) : "Default range"}</span>}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" collisionPadding={16} className="max-h-(--radix-popover-content-available-height) w-auto max-w-[calc(100vw-2rem)] overflow-y-auto p-3">
        <div className="grid gap-3">
          {compare && <CompareToggle {...compare} />}
          <div className="flex items-center gap-2">
            <Input
              type="date"
              aria-label="First day"
              className="h-8 w-40"
              value={dates?.from ?? ""}
              onChange={(event) => dates && chooseDates(event.target.value, dates.to)}
            />
            <span aria-hidden>–</span>
            <Input
              type="date"
              aria-label="Last day"
              className="h-8 w-40"
              value={dates?.to ?? ""}
              onChange={(event) => dates && chooseDates(dates.from, event.target.value)}
            />
          </div>
          <div className="flex flex-col gap-3 sm:flex-row">
            <div role="group" aria-label="Presets" className="flex flex-row flex-wrap gap-1 sm:w-36 sm:flex-col">
              <PresetButton label="Default" selected={value === null} onClick={() => onChange(null)} />
              {presets.map((preset) => (
                <PresetButton
                  key={preset.label}
                  label={preset.label}
                  selected={value !== null && sameRange(value, preset.range)}
                  onClick={() => onChange(preset.range)}
                />
              ))}
            </div>
            <Calendar
              mode="range"
              numberOfMonths={2}
              weekStartsOn={1}
              defaultMonth={selected?.from}
              selected={selected}
              onSelect={(range, day) => {
                // A range that is already whole starts again from the clicked day.
                if (!pending) return setPending({ from: day, to: undefined })
                const ends = [pending.from ?? day, range?.to ?? day].sort((a, b) => a.getTime() - b.getTime())
                chooseDates(ymd(ends[0]), ymd(ends[1]))
              }}
            />
          </div>
        </div>
      </PopoverContent>
    </Popover>
  )
}

/** A switch that turns the comparison on, and the earlier window it compares with. */
function CompareToggle({ value, onChange }: { value: ReportCompare | null; onChange: (value: ReportCompare | null) => void }) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Switch id="compare-toggle" checked={value !== null} onCheckedChange={(on) => onChange(on ? firstCompare : null)} />
      <Label htmlFor="compare-toggle">Compare to</Label>
      <div role="group" aria-label="Compare with" className="flex gap-1">
        {(["previousYear", "previousPeriod"] as const).map((mode) => (
          <Button
            key={mode}
            size="sm"
            variant={value === mode ? "secondary" : "ghost"}
            aria-pressed={value === mode}
            disabled={value === null}
            onClick={() => onChange(mode)}
          >
            {describeCompare(mode)}
          </Button>
        ))}
      </div>
    </div>
  )
}

function PresetButton({ label, selected, onClick }: { label: string; selected: boolean; onClick: () => void }) {
  return (
    <Button size="sm" variant={selected ? "secondary" : "ghost"} aria-pressed={selected} className="justify-start" onClick={onClick}>
      {label}
    </Button>
  )
}

const homeRangeKey = "basekt.reportRange"

/** The home screen's range for every report, kept in this browser so it opens on it again. `null` means each report's own. */
export function useRangeChoice(): [RangeChoice | null, (choice: RangeChoice | null) => void] {
  const [choice, setChoice] = useState<RangeChoice | null>(() => {
    try {
      const saved = JSON.parse(localStorage.getItem(homeRangeKey) ?? "null") as RangeChoice | null
      return saved && (("from" in saved && "to" in saved) || ("last" in saved && "unit" in saved)) ? saved : null
    } catch {
      return null
    }
  })
  const choose = useCallback((next: RangeChoice | null) => {
    setChoice(next)
    try {
      if (next) localStorage.setItem(homeRangeKey, JSON.stringify(next))
      else localStorage.removeItem(homeRangeKey)
    } catch {
      // Storage may be off, as in a private window: the choice then lasts as long as the page.
    }
  }, [])
  return [choice, choose]
}

function sameRange(a: RangeChoice, b: RollingRange): boolean {
  return "last" in a && a.last === b.last && a.unit === b.unit && (a.skip ?? 0) === (b.skip ?? 0)
}

/** The day a calendar date stands for, as `YYYY-MM-DD`: the calendar picks local midnights, so local parts. */
function ymd(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`
}

function fromYmd(text: string): Date {
  const [year, month, day] = text.split("-").map(Number)
  return new Date(year, month - 1, day)
}

function capitalize(text: string): string {
  return text[0].toUpperCase() + text.slice(1)
}
