import { useState } from "react"
import { Check, ChevronsUpDown, X } from "lucide-react"
import type { AdminResource, JsonObject } from "@wire"
import { Button } from "@/components/ui/button"
import { Command, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList } from "@/components/ui/command"
import { Input } from "@/components/ui/input"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import type { WidgetProps } from "@/forms/widgets"
import { recordId } from "@/lib/fields"
import { useAsync } from "@/lib/use-async"
import { cn } from "@/lib/utils"
import { usePanel, useResource } from "@/panel"

/** A record's label in a picker: its first searchable field, or else its first text value. */
export function labelOf(resource: AdminResource, record: JsonObject): string | null {
  for (const name of [...resource.searchable, ...resource.fields.map((f) => f.name)]) {
    const value = record[name]
    if (typeof value === "string" && value !== "") return value
  }
  return null
}

/**
 * Picks a record of the field's target resource, searching it through the admin API as the viewer types. When
 * the caller may not read the target, it falls back to typing the id.
 */
export function ReferencePicker({ field, id, value, onChange, invalid, describedBy }: WidgetProps) {
  const target = useResource(field.target ?? "")
  if (!target) {
    return (
      <Input
        id={id}
        value={value == null ? "" : String(value)}
        onChange={(e) => onChange(e.target.value === "" ? null : e.target.value)}
        aria-invalid={invalid}
        aria-describedby={describedBy}
        placeholder={`${field.target ?? "Record"} id`}
      />
    )
  }
  return <Picker target={target} id={id} value={value == null ? null : String(value)} onChange={onChange} invalid={invalid} describedBy={describedBy} required={field.required} />
}

function Picker({ target, id, value, onChange, invalid, describedBy, required }: {
  target: AdminResource
  id: string
  value: string | null
  onChange: (value: string | null) => void
  invalid: boolean
  describedBy?: string
  required: boolean
}) {
  const { api } = usePanel()
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState("")
  const searchable = target.searchable.length > 0
  const options = useAsync(
    () => (open ? api.list(target.name, { limit: "20", ...(searchable && query.trim() ? { search: query.trim() } : {}) }) : Promise.resolve(null)),
    [api, target.name, open, query],
  )
  const selected = useAsync(() => (value ? api.get(target.name, value).catch(() => null) : Promise.resolve(null)), [api, target.name, value])
  const selectedLabel = selected.data ? labelOf(target, selected.data) : null

  return (
    <div className="flex items-center gap-1">
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button
            id={id}
            type="button"
            variant="outline"
            role="combobox"
            aria-expanded={open}
            aria-invalid={invalid}
            aria-describedby={describedBy}
            className="w-full justify-between font-normal sm:w-72"
          >
            <span className={cn("truncate", !value && "text-muted-foreground")}>
              {value ? (selectedLabel ? `${selectedLabel} (${value})` : value) : "Choose…"}
            </span>
            <ChevronsUpDown className="opacity-50" />
          </Button>
        </PopoverTrigger>
        <PopoverContent className="w-72 p-0" align="start">
          <Command shouldFilter={false}>
            {searchable && <CommandInput placeholder="Search…" value={query} onValueChange={setQuery} />}
            <CommandList>
              <CommandEmpty>{options.loading ? "Searching…" : "No matches."}</CommandEmpty>
              <CommandGroup>
                {(options.data?.items ?? []).map((record) => {
                  const recordKey = recordId(record)
                  return (
                    <CommandItem
                      key={recordKey}
                      value={recordKey}
                      onSelect={() => {
                        onChange(recordKey)
                        setOpen(false)
                      }}
                    >
                      <Check className={cn(recordKey === value ? "opacity-100" : "opacity-0")} />
                      <span className="truncate">{labelOf(target, record) ?? recordKey}</span>
                      <span className="ml-auto font-mono text-xs text-muted-foreground">{recordKey}</span>
                    </CommandItem>
                  )
                })}
              </CommandGroup>
            </CommandList>
          </Command>
        </PopoverContent>
      </Popover>
      {value && !required && (
        <Button type="button" variant="ghost" size="icon" aria-label="Clear" onClick={() => onChange(null)}>
          <X />
        </Button>
      )}
    </div>
  )
}
