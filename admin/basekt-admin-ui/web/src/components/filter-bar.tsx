import { useState, type FormEvent } from "react"
import { Filter as FilterIcon, X } from "lucide-react"
import type { AdminField, AdminResource } from "@wire"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { filterChoices, filterParam, filtersOf, humanize, opLabels, type Filter, type FilterOp } from "@/lib/fields"

/** The choices of a new filter: each field a list may be filtered on, with the operators it takes. */
type Choices = ReturnType<typeof filterChoices>

/**
 * The active filters as removable chips, and a row to add one: a field, an operator that suits it and the resource
 * accepts, and a value.
 */
export function FilterBar({ resource, search, setSearch }: {
  resource: AdminResource
  search: URLSearchParams
  setSearch: (next: URLSearchParams) => void
}) {
  const [adding, setAdding] = useState(false)
  const filters = filtersOf(resource, search)
  const choices = filterChoices(resource)

  const remove = (filter: Filter) => {
    const [key] = filterParam(filter)
    const next = new URLSearchParams(search)
    next.delete(key)
    setSearch(next)
  }
  const add = (filter: Filter) => {
    const [key, value] = filterParam(filter)
    const next = new URLSearchParams(search)
    next.set(key, value)
    setSearch(next)
    setAdding(false)
  }

  return (
    <div className="flex flex-wrap items-center gap-2">
      {filters.map((filter) => (
        <Badge key={filterParam(filter)[0]} variant="secondary" className="h-9 gap-1 pr-1 text-sm font-normal">
          {humanize(filter.field)} {opLabels[filter.op]} {filter.op === "null" ? "" : <strong>{filter.value}</strong>}
          <button type="button" aria-label={`Remove filter on ${humanize(filter.field)}`} className="rounded-sm p-1 hover:bg-background" onClick={() => remove(filter)}>
            <X className="size-3.5" />
          </button>
        </Badge>
      ))}
      {adding ? (
        <NewFilter choices={choices} onAdd={add} onCancel={() => setAdding(false)} />
      ) : (
        <Button variant="outline" onClick={() => setAdding(true)} disabled={choices.length === 0}>
          <FilterIcon /> Filter
        </Button>
      )}
    </div>
  )
}

function NewFilter({ choices, onAdd, onCancel }: { choices: Choices; onAdd: (filter: Filter) => void; onCancel: () => void }) {
  const [name, setName] = useState(choices[0].field.name)
  const { field, ops } = choices.find((c) => c.field.name === name) ?? choices[0]
  const [op, setOp] = useState<FilterOp>(ops[0])
  const [value, setValue] = useState("")
  const currentOp = ops.includes(op) ? op : ops[0]

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (currentOp !== "null" && value === "") return
    onAdd({ field: field.name, op: currentOp, value })
  }

  return (
    <form onSubmit={submit} aria-label="New filter" className="flex flex-wrap items-center gap-2">
      <Select value={field.name} onValueChange={(next) => { setName(next); setValue("") }}>
        <SelectTrigger aria-label="Field" className="w-36">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {choices.map(({ field: f }) => (
            <SelectItem key={f.name} value={f.name}>{humanize(f.name)}</SelectItem>
          ))}
        </SelectContent>
      </Select>
      <Select value={currentOp} onValueChange={(next) => setOp(next as FilterOp)}>
        <SelectTrigger aria-label="Operator" className="w-28">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {ops.map((o) => (
            <SelectItem key={o} value={o}>{opLabels[o]}</SelectItem>
          ))}
        </SelectContent>
      </Select>
      {currentOp !== "null" && <FilterValue field={field} value={value} setValue={setValue} />}
      <Button type="submit" disabled={currentOp !== "null" && value === ""}>Apply</Button>
      <Button type="button" variant="ghost" onClick={onCancel}>Cancel</Button>
    </form>
  )
}

function FilterValue({ field, value, setValue }: { field: AdminField; value: string; setValue: (value: string) => void }) {
  const choices = field.type === "bool" ? ["true", "false"] : field.type === "enum" ? (field.values ?? []) : null
  if (choices) {
    return (
      <Select value={value} onValueChange={setValue}>
        <SelectTrigger aria-label="Value" className="w-36">
          <SelectValue placeholder="Value" />
        </SelectTrigger>
        <SelectContent>
          {choices.map((choice) => (
            <SelectItem key={choice} value={choice}>{choice}</SelectItem>
          ))}
        </SelectContent>
      </Select>
    )
  }
  const numeric = field.type === "int" || field.type === "long" || field.type === "decimal"
  return (
    <Input
      aria-label="Value"
      className="w-44"
      inputMode={numeric ? "decimal" : undefined}
      placeholder={field.type === "instant" ? "2026-01-31T00:00:00Z" : "Value"}
      value={value}
      onChange={(e) => setValue(e.target.value)}
    />
  )
}
