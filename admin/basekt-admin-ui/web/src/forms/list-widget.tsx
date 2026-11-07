import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from "react"
import { ArrowDown, ArrowLeft, ArrowRight, ArrowUp, Check, Plus, Trash2 } from "lucide-react"
import type { AdminField, JsonObject, JsonValue, ListStyle } from "@wire"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import { widgetFor, type WidgetProps } from "@/forms/widgets"
import { humanize, itemNoun } from "@/lib/fields"
import { cn } from "@/lib/utils"

/**
 * A list field, edited item by item: each item with the widget of the list's type (a text box, a Markdown editor, a
 * reference picker...), or, for an object list, the item's own fields. Items are added, removed and moved. The
 * resource's `listStyle` lays them out as cards (the default), as rows of one line each, or as tabs with a tab that
 * adds one. An object item keeps its `id`, unseen, so the server knows it is the same item. An empty item of a plain
 * list is left out of what is saved, so a half-typed new line never fails the save.
 */
export function ListWidget({ field, id, value, onChange, invalid, describedBy, listStyle }: WidgetProps) {
  const objects = field.type === "object"
  const noun = itemNoun(field)
  const style: ListStyle = listStyle ?? "cards"
  const incoming = Array.isArray(value) ? value : []
  const [items, setItems] = useState<JsonValue[]>(incoming)
  const nextKey = useRef(incoming.length)
  const [keys, setKeys] = useState<number[]>(() => incoming.map((_, i) => i))
  const [selected, setSelected] = useState(0)
  // What this widget last sent, so a value that comes back unchanged doesn't reset what is being typed.
  const sent = useRef(JSON.stringify(incoming))
  useEffect(() => {
    const next = Array.isArray(value) ? value : []
    if (JSON.stringify(next) === sent.current) return
    sent.current = JSON.stringify(next)
    setItems(next)
    setKeys(next.map(() => nextKey.current++))
  }, [value])

  const emit = (next: JsonValue[], nextKeys: number[]) => {
    setItems(next)
    setKeys(nextKeys)
    const kept = objects ? next : next.filter((item) => item !== null && item !== "")
    sent.current = JSON.stringify(kept)
    onChange(kept.length === 0 && !field.required ? null : kept)
  }
  const set = (index: number, item: JsonValue) => emit(items.map((old, i) => (i === index ? item : old)), keys)
  const remove = (index: number) => {
    emit(items.filter((_, i) => i !== index), keys.filter((_, i) => i !== index))
    setSelected((current) => Math.max(0, Math.min(current, items.length - 2)))
  }
  const move = (index: number, by: number) => {
    const to = index + by
    const swap = <T,>(list: T[]) => list.map((old, i) => (i === index ? list[to] : i === to ? list[index] : old))
    emit(swap(items), swap(keys))
    if (style === "tabs") setSelected(to)
  }
  const add = () => {
    emit([...items, objects ? {} : null], [...keys, nextKey.current++])
    setSelected(items.length)
  }

  const itemField: AdminField = { ...field, list: false, required: false }
  const Item = objects ? null : widgetFor(itemField)
  const body = (item: JsonValue, index: number, inline: boolean) => {
    const itemId = `${id}-${keys[index]}`
    return Item ? (
      <Item field={itemField} id={itemId} value={item} onChange={(next) => set(index, next)} invalid={false} dense />
    ) : (
      <ObjectItem field={field} id={itemId} value={item} inline={inline} onChange={(next) => set(index, next)} />
    )
  }
  const action = (label: string, icon: ReactNode, onClick: () => void, disabled = false, small = false) => (
    <Button type="button" variant="ghost" size="icon" className={small ? "size-8" : undefined} aria-label={label} disabled={disabled} onClick={onClick}>
      {icon}
    </Button>
  )
  const empty = <p className="text-muted-foreground text-sm">No {noun}s yet.</p>

  if (style === "tabs") {
    const current = Math.min(selected, items.length - 1)
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "ArrowRight" && current < items.length - 1) setSelected(current + 1)
      else if (event.key === "ArrowLeft" && current > 0) setSelected(current - 1)
    }
    return (
      <div id={id} aria-describedby={describedBy} className="space-y-0">
        <div role="tablist" aria-label={humanize(field.name)} onKeyDown={onKeyDown} className="flex flex-wrap items-end gap-1 border-b">
          {items.map((item, index) => (
            <button
              key={keys[index]}
              type="button"
              role="tab"
              id={`${id}-tab-${keys[index]}`}
              aria-selected={index === current}
              aria-controls={`${id}-panel`}
              tabIndex={index === current ? 0 : -1}
              onClick={() => setSelected(index)}
              className={cn(
                "-mb-px flex max-w-48 items-center gap-1 rounded-t-md border border-b-0 px-3 py-1.5 text-sm",
                index === current ? "bg-background text-foreground border-border" : "text-muted-foreground hover:text-foreground border-transparent",
                invalid && "text-destructive",
              )}
            >
              <span className="truncate">{tabLabel(field, item, index)}</span>
              {checked(field, item) && <Check aria-label="checked" className="text-primary size-3.5 shrink-0" />}
            </button>
          ))}
          <button type="button" aria-label={`Add ${noun}`} title={`Add ${noun}`} onClick={add} className="text-muted-foreground hover:text-foreground mb-0.5 flex size-8 items-center justify-center rounded-md">
            <Plus className="size-4" />
          </button>
        </div>
        {items.length === 0 ? (
          <div className="pt-3">{empty}</div>
        ) : (
          <div role="tabpanel" id={`${id}-panel`} aria-labelledby={`${id}-tab-${keys[current]}`} data-slot="list-item" className={cn("space-y-3 rounded-b-md border border-t-0 p-3", invalid && "border-destructive/50")}>
            {body(items[current], current, false)}
            <div className="flex justify-end gap-0.5">
              {action(`Move ${noun} ${current + 1} left`, <ArrowLeft />, () => move(current, -1), current === 0)}
              {action(`Move ${noun} ${current + 1} right`, <ArrowRight />, () => move(current, 1), current === items.length - 1)}
              {action(`Remove ${noun} ${current + 1}`, <Trash2 />, () => remove(current))}
            </div>
          </div>
        )}
      </div>
    )
  }

  const rows = style === "rows"
  return (
    <div id={id} aria-describedby={describedBy} className="space-y-2">
      {items.length === 0 ? (
        empty
      ) : (
        <ol className={cn(rows ? "divide-y rounded-md border" : "space-y-2", rows && invalid && "border-destructive/50")}>
          {items.map((item, index) => (
            <li
              key={keys[index]}
              data-slot="list-item"
              aria-label={`${humanize(noun)} ${index + 1}`}
              className={cn(rows ? "px-2 py-1.5" : "rounded-md border p-3", !rows && invalid && "border-destructive/50")}
            >
              {/* In a narrow column (a form's aside) the buttons wrap under the item, so its widget keeps the width. */}
              <div className={cn("flex flex-wrap gap-2", rows ? "items-center" : "items-start")}>
                <span className={cn("text-muted-foreground w-5 shrink-0 text-xs tabular-nums", !rows && "mt-2")}>{index + 1}</span>
                <div className={cn("min-w-0 flex-[1_1_10rem]", !rows && "space-y-3")}>{body(item, index, rows)}</div>
                <div className="ml-auto flex shrink-0 gap-0.5">
                  {action(`Move ${noun} ${index + 1} up`, <ArrowUp />, () => move(index, -1), index === 0, rows)}
                  {action(`Move ${noun} ${index + 1} down`, <ArrowDown />, () => move(index, 1), index === items.length - 1, rows)}
                  {action(`Remove ${noun} ${index + 1}`, <Trash2 />, () => remove(index), false, rows)}
                </div>
              </div>
            </li>
          ))}
        </ol>
      )}
      <Button type="button" variant="outline" size="sm" onClick={add}>
        <Plus /> Add {noun}
      </Button>
    </div>
  )
}

/** An item as a tab's label: its first text field's words, its own value for a plain list, or "Option 2". */
function tabLabel(field: AdminField, item: JsonValue, index: number): string {
  const fallback = `${humanize(itemNoun(field))} ${index + 1}`
  if (typeof item === "string" || typeof item === "number") return plain(String(item)) || fallback
  if (item === null || typeof item !== "object" || Array.isArray(item)) return fallback
  const text = (field.fields ?? []).find((sub) => sub.type === "string" && typeof item[sub.name] === "string" && item[sub.name] !== "")
  return text ? plain(String(item[text.name])) || fallback : fallback
}

/** Whether an object item's first true-or-false field is on, such as an option marked correct. */
function checked(field: AdminField, item: JsonValue): boolean {
  if (item === null || typeof item !== "object" || Array.isArray(item)) return false
  const flag = (field.fields ?? []).find((sub) => sub.type === "bool")
  return flag !== undefined && item[flag.name] === true
}

/** Markdown as a short plain line for a label: a picture is "Picture", and the marks are dropped. */
function plain(markdown: string): string {
  return markdown
    .replace(/!\[[^\]]*]\([^)]*\)/g, "Picture")
    .replace(/\[([^\]]*)]\([^)]*\)/g, "$1")
    .replace(/[*_~`$#>\\]/g, "")
    .replace(/\s+/g, " ")
    .trim()
}

/**
 * One item of an object list: its fields, each with its own widget. A true-or-false field sits beside its label. In a
 * row ([inline]) the fields sit side by side, the text ones taking the width, and only the true-or-false ones are
 * labelled on screen; the others are labelled for screen readers.
 */
function ObjectItem({ field, id, value, inline, onChange }: { field: AdminField; id: string; value: JsonValue; inline: boolean; onChange: (value: JsonValue) => void }) {
  const item: JsonObject = value !== null && typeof value === "object" && !Array.isArray(value) ? value : {}
  const fields = (field.fields ?? []).map((sub) => {
    const Widget = widgetFor(sub)
    const subId = `${id}-${sub.name}`
    const label = (
      <Label htmlFor={subId} className={cn("text-xs", inline && sub.type !== "bool" && "sr-only")}>
        {humanize(sub.name)}
        {sub.required && <span className="text-destructive"> *</span>}
      </Label>
    )
    const widget = <Widget field={sub} id={subId} value={item[sub.name]} onChange={(next) => onChange({ ...item, [sub.name]: next })} invalid={false} dense />
    if (sub.type === "bool") {
      return (
        <div key={sub.name} className="flex shrink-0 items-center gap-2">
          {widget}
          {label}
        </div>
      )
    }
    return (
      <div key={sub.name} className={inline ? "min-w-48 flex-1" : "space-y-1.5"}>
        {label}
        {widget}
      </div>
    )
  })
  return inline ? <div className="flex flex-wrap items-center gap-3">{fields}</div> : <>{fields}</>
}
