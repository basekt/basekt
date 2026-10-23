import { CalendarRange } from "lucide-react"
import { useCallback, useState } from "react"
import type { DateRange } from "react-day-picker"
import type { AdminReport, ReportCompare } from "@wire"
import { rangeChoices } from "@/components/range-picker"
import { Button } from "@/components/ui/button"
import { Calendar } from "@/components/ui/calendar"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { compareOf, describeCompare, describeRange, type CustomRange, type RangeChoice } from "@/lib/reports"

/**
 * What a viewer chose for one report card: a range in place of the home screen's and the report's, and a comparison
 * in place of the report's, where `null` turns it off. Missing means the card follows the defaults.
 */
export interface CardChoice {
  range?: RangeChoice
  compare?: ReportCompare | null
}

/** The viewer's choice for the card of [report] on [resource], kept in this browser so the card opens on it again. */
export function useCardChoice(resource: string, report: string): [CardChoice, (choice: CardChoice) => void] {
  const storageKey = `basekt.report.${resource}.${report}`
  const [choice, setChoice] = useState<CardChoice>(() => {
    try {
      const saved = localStorage.getItem(storageKey)
      return saved ? (JSON.parse(saved) as CardChoice) : {}
    } catch {
      return {}
    }
  })
  const choose = useCallback((next: CardChoice) => {
    setChoice(next)
    try {
      if (next.range === undefined && next.compare === undefined) localStorage.removeItem(storageKey)
      else localStorage.setItem(storageKey, JSON.stringify(next))
    } catch {
      // Storage may be off, as in a private window: the choice then lasts as long as the page.
    }
  }, [storageKey])
  return [choice, choose]
}

/**
 * The range menu of a report card: each report's own range or a rolling one, dates picked on a calendar, and the
 * earlier window to compare with. [title] names the card for screen readers.
 */
export function ReportRangeMenu({ report, title, choice, onChange }: {
  report: AdminReport
  title: string
  choice: CardChoice
  onChange: (choice: CardChoice) => void
}) {
  const [picking, setPicking] = useState(false)
  const range = choice.range
  const rangeValue = range === undefined ? "default" : "from" in range ? "custom" : key(range)
  const compareValue = compareOf(report, { compare: choice.compare }) ?? "off"
  return (
    <>
      <DropdownMenu modal={false}>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="sm" aria-label={`Range of ${title}`}>
            <CalendarRange />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-56">
          <DropdownMenuLabel>Range</DropdownMenuLabel>
          <DropdownMenuRadioGroup
            value={rangeValue}
            onValueChange={(value) => {
              if (value === "custom") return
              onChange({ ...choice, range: value === "default" ? undefined : rangeChoices.find((c) => key(c) === value) })
            }}
          >
            <DropdownMenuRadioItem value="default">Default</DropdownMenuRadioItem>
            {rangeChoices.map((c) => (
              <DropdownMenuRadioItem key={key(c)} value={key(c)}>
                {capitalize(describeRange(c))}
              </DropdownMenuRadioItem>
            ))}
            <DropdownMenuRadioItem value="custom" onSelect={() => setPicking(true)}>
              {range && "from" in range ? describeRange(range) : "Custom dates…"}
            </DropdownMenuRadioItem>
          </DropdownMenuRadioGroup>
          {report.chart !== "pie" && (
            <>
              <DropdownMenuSeparator />
              <DropdownMenuLabel>Compare with</DropdownMenuLabel>
              <DropdownMenuRadioGroup
                value={compareValue}
                onValueChange={(value) => onChange({ ...choice, compare: value === "off" ? null : (value as ReportCompare) })}
              >
                <DropdownMenuRadioItem value="off">Nothing</DropdownMenuRadioItem>
                <DropdownMenuRadioItem value="previousPeriod">{capitalize(describeCompare("previousPeriod"))}</DropdownMenuRadioItem>
                <DropdownMenuRadioItem value="previousYear">{capitalize(describeCompare("previousYear"))}</DropdownMenuRadioItem>
              </DropdownMenuRadioGroup>
            </>
          )}
          {(choice.range !== undefined || choice.compare !== undefined) && (
            <>
              <DropdownMenuSeparator />
              <DropdownMenuItem onSelect={() => onChange({})}>Reset</DropdownMenuItem>
            </>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
      <CustomRangeDialog
        open={picking}
        title={title}
        initial={range && "from" in range ? range : undefined}
        onCancel={() => setPicking(false)}
        onApply={(custom) => {
          setPicking(false)
          onChange({ ...choice, range: custom })
        }}
      />
    </>
  )
}

/** A two-month calendar to pick the first and last day of a range, both included. */
function CustomRangeDialog({ open, title, initial, onCancel, onApply }: {
  open: boolean
  title: string
  initial?: CustomRange
  onCancel: () => void
  onApply: (range: CustomRange) => void
}) {
  const [selected, setSelected] = useState<DateRange | undefined>(() =>
    initial ? { from: fromYmd(initial.from), to: fromYmd(initial.to) } : undefined,
  )
  return (
    <Dialog open={open} onOpenChange={(next) => !next && onCancel()}>
      <DialogContent className="sm:max-w-fit">
        <DialogHeader>
          <DialogTitle>Dates of {title}</DialogTitle>
          <DialogDescription>Pick the first and the last day. Both are included, in the report's time zone.</DialogDescription>
        </DialogHeader>
        <Calendar mode="range" numberOfMonths={2} selected={selected} onSelect={setSelected} defaultMonth={selected?.from} />
        <DialogFooter>
          <Button variant="ghost" onClick={onCancel}>Cancel</Button>
          <Button
            disabled={!selected?.from}
            onClick={() => selected?.from && onApply({ from: ymd(selected.from), to: ymd(selected.to ?? selected.from) })}
          >
            Apply
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/** The day a calendar date stands for, as `YYYY-MM-DD`: the calendar picks local midnights, so local parts. */
function ymd(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`
}

function fromYmd(text: string): Date {
  const [year, month, day] = text.split("-").map(Number)
  return new Date(year, month - 1, day)
}

function key(range: { last: number; unit: string }): string {
  return `${range.last}${range.unit}`
}

function capitalize(text: string): string {
  return text[0].toUpperCase() + text.slice(1)
}
