import { useCallback, useState } from "react"
import type { ReportCompare } from "@wire"
import type { RangeChoice } from "@/lib/reports"

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
