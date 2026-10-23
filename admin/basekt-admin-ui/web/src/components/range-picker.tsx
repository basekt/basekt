import { useCallback, useState } from "react"
import { Button } from "@/components/ui/button"
import { describeRange, type RangeChoice } from "@/lib/reports"

/** The windows a viewer can pick for every report with a range, besides each report's own. */
export const rangeChoices: RangeChoice[] = [
  { last: 1, unit: "day" },
  { last: 7, unit: "day" },
  { last: 30, unit: "day" },
  { last: 90, unit: "day" },
  { last: 12, unit: "month" },
]

const storageKey = "basekt.reportRange"

/** The viewer's range, kept in this browser so the home screen opens on it again. `null` means each report's own. */
export function useRangeChoice(): [RangeChoice | null, (choice: RangeChoice | null) => void] {
  const [choice, setChoice] = useState<RangeChoice | null>(() => {
    try {
      const saved = localStorage.getItem(storageKey)
      return rangeChoices.find((c) => saved === key(c)) ?? null
    } catch {
      return null
    }
  })
  const choose = useCallback((next: RangeChoice | null) => {
    setChoice(next)
    try {
      if (next) localStorage.setItem(storageKey, key(next))
      else localStorage.removeItem(storageKey)
    } catch {
      // Storage may be off, as in a private window: the choice then lasts as long as the page.
    }
  }, [])
  return [choice, choose]
}

/** Buttons that set the window of every report with a range: each report's own, or one for all of them. */
export function RangePicker({ value, onChange }: { value: RangeChoice | null; onChange: (choice: RangeChoice | null) => void }) {
  const option = (choice: RangeChoice | null, label: string) => {
    const selected = (choice && value ? key(choice) === key(value) : choice === value)
    return (
      <Button key={choice ? key(choice) : "own"} size="sm" variant={selected ? "secondary" : "ghost"} aria-pressed={selected} onClick={() => onChange(choice)}>
        {label}
      </Button>
    )
  }
  return (
    <div role="group" aria-label="Report range" className="flex flex-wrap items-center gap-1">
      {option(null, "Default")}
      {rangeChoices.map((choice) => option(choice, capitalize(describeRange(choice))))}
    </div>
  )
}

function key(choice: RangeChoice): string {
  return `${choice.last}${choice.unit}`
}

function capitalize(text: string): string {
  return text[0].toUpperCase() + text.slice(1)
}
