import { useEffect, useRef, useState } from "react"
import { ArrowDown, ArrowUp, Plus, Trash2 } from "lucide-react"
import type { AdminField, JsonObject, JsonValue } from "@wire"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import { widgetFor, type WidgetProps } from "@/forms/widgets"
import { humanize, itemNoun } from "@/lib/fields"
import { cn } from "@/lib/utils"

/**
 * A list field, edited item by item: each item with the widget of the list's type (a text box, a Markdown editor, a
 * reference picker...), or, for an object list, a card with the item's own fields. Items are added, removed and
 * moved up or down. An object item keeps its `id`, unseen, so the server knows it is the same item. An empty item of
 * a plain list is left out of what is saved, so a half-typed new line never fails the save.
 */
export function ListWidget({ field, id, value, onChange, invalid, describedBy }: WidgetProps) {
  const objects = field.type === "object"
  const noun = itemNoun(field)
  const incoming = Array.isArray(value) ? value : []
  const [items, setItems] = useState<JsonValue[]>(incoming)
  const nextKey = useRef(incoming.length)
  const [keys, setKeys] = useState<number[]>(() => incoming.map((_, i) => i))
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
  const remove = (index: number) => emit(items.filter((_, i) => i !== index), keys.filter((_, i) => i !== index))
  const move = (index: number, by: number) => {
    const to = index + by
    const swap = <T,>(list: T[]) => list.map((old, i) => (i === index ? list[to] : i === to ? list[index] : old))
    emit(swap(items), swap(keys))
  }
  const add = () => emit([...items, objects ? {} : null], [...keys, nextKey.current++])

  const itemField: AdminField = { ...field, list: false, required: false }
  const Item = objects ? null : widgetFor(itemField)

  return (
    <div id={id} aria-describedby={describedBy} className="space-y-2">
      {items.length === 0 ? (
        <p className="text-muted-foreground text-sm">No {noun}s yet.</p>
      ) : (
        <ol className="space-y-2">
          {items.map((item, index) => {
            const itemId = `${id}-${keys[index]}`
            return (
              <li key={keys[index]} data-slot="list-item" aria-label={`${humanize(noun)} ${index + 1}`} className={cn("rounded-md border p-3", invalid && "border-destructive/50")}>
                <div className="flex items-start gap-2">
                  <span className="text-muted-foreground mt-2 w-5 shrink-0 text-xs tabular-nums">{index + 1}</span>
                  <div className="min-w-0 flex-1 space-y-3">
                    {Item ? (
                      <Item field={itemField} id={itemId} value={item} onChange={(next) => set(index, next)} invalid={false} />
                    ) : (
                      <ObjectItem field={field} id={itemId} value={item} onChange={(next) => set(index, next)} />
                    )}
                  </div>
                  <div className="flex shrink-0 gap-0.5">
                    <Button type="button" variant="ghost" size="icon" aria-label={`Move ${noun} ${index + 1} up`} disabled={index === 0} onClick={() => move(index, -1)}>
                      <ArrowUp />
                    </Button>
                    <Button type="button" variant="ghost" size="icon" aria-label={`Move ${noun} ${index + 1} down`} disabled={index === items.length - 1} onClick={() => move(index, 1)}>
                      <ArrowDown />
                    </Button>
                    <Button type="button" variant="ghost" size="icon" aria-label={`Remove ${noun} ${index + 1}`} onClick={() => remove(index)}>
                      <Trash2 />
                    </Button>
                  </div>
                </div>
              </li>
            )
          })}
        </ol>
      )}
      <Button type="button" variant="outline" size="sm" onClick={add}>
        <Plus /> Add {noun}
      </Button>
    </div>
  )
}

/** One item of an object list: its fields, each with its own widget. A true-or-false field sits beside its label. */
function ObjectItem({ field, id, value, onChange }: { field: AdminField; id: string; value: JsonValue; onChange: (value: JsonValue) => void }) {
  const item: JsonObject = value !== null && typeof value === "object" && !Array.isArray(value) ? value : {}
  return (
    <>
      {(field.fields ?? []).map((sub) => {
        const Widget = widgetFor(sub)
        const subId = `${id}-${sub.name}`
        const label = (
          <Label htmlFor={subId} className="text-xs">
            {humanize(sub.name)}
            {sub.required && <span className="text-destructive"> *</span>}
          </Label>
        )
        const widget = <Widget field={sub} id={subId} value={item[sub.name]} onChange={(next) => onChange({ ...item, [sub.name]: next })} invalid={false} />
        return sub.type === "bool" ? (
          <div key={sub.name} className="flex items-center gap-2">
            {widget}
            {label}
          </div>
        ) : (
          <div key={sub.name} className="space-y-1.5">
            {label}
            {widget}
          </div>
        )
      })}
    </>
  )
}
