import { useState, type ReactNode } from "react"
import type { AdminField, FieldType, JsonValue } from "@wire"
import { ReferencePicker } from "@/forms/reference-picker"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Switch } from "@/components/ui/switch"

/** What a widget gets: its field, the control's id for the label, the current JSON value, and how to change it. */
export interface WidgetProps {
  field: AdminField
  id: string
  value: JsonValue | undefined
  onChange: (value: JsonValue) => void
  invalid: boolean
  describedBy?: string
}

/** A form control for one kind of field. It reads and writes the value as the admin API's JSON has it. */
export type Widget = (props: WidgetProps) => ReactNode

/**
 * The widget for each field type. The panel renders every form from this table, so a resource never needs code of
 * its own. A type the panel doesn't know yet, which a newer API may add, gets [fallbackWidget].
 */
export const widgets: Record<FieldType, Widget> = {
  string: TextWidget,
  int: IntegerWidget,
  long: IntegerWidget,
  decimal: DecimalWidget,
  bool: BoolWidget,
  instant: InstantWidget,
  enum: EnumWidget,
  reference: ReferenceWidget,
}

/** The widget for a type this panel doesn't know: a text box, so the value can still be read and edited. */
export const fallbackWidget: Widget = TextWidget

/** The widget for [field]. */
export function widgetFor(field: AdminField): Widget {
  return (widgets as Partial<Record<string, Widget>>)[field.type] ?? fallbackWidget
}

function TextWidget({ field, id, value, onChange, invalid, describedBy }: WidgetProps) {
  return (
    <Input
      id={id}
      value={value == null ? "" : String(value)}
      onChange={(e) => onChange(e.target.value === "" && !field.required ? null : e.target.value)}
      aria-invalid={invalid}
      aria-describedby={describedBy}
      maxLength={maxLength(field)}
    />
  )
}

/**
 * Whole numbers. What the viewer types is kept as text until it parses, so "-" or "1e" aren't lost while typing; a
 * value that never parses is sent as text, and the API answers with a type error on the field.
 */
function IntegerWidget({ field, id, value, onChange, invalid, describedBy }: WidgetProps) {
  const [text, setText] = useState(value == null ? "" : String(value))
  const change = (next: string) => {
    setText(next)
    const trimmed = next.trim()
    if (trimmed === "") onChange(null)
    else if (/^-?\d+$/.test(trimmed) && Number.isSafeInteger(Number(trimmed))) onChange(Number(trimmed))
    else onChange(trimmed)
  }
  return (
    <Input
      id={id}
      inputMode="numeric"
      value={text}
      onChange={(e) => change(e.target.value)}
      aria-invalid={invalid}
      aria-describedby={describedBy}
      required={field.required}
    />
  )
}

/** Decimals stay text end to end, as the API sends them, so no digit is lost to floating point. */
function DecimalWidget({ id, value, onChange, invalid, describedBy, field }: WidgetProps) {
  return (
    <Input
      id={id}
      inputMode="decimal"
      value={value == null ? "" : String(value)}
      onChange={(e) => onChange(e.target.value.trim() === "" ? null : e.target.value.trim())}
      aria-invalid={invalid}
      aria-describedby={describedBy}
      required={field.required}
    />
  )
}

function BoolWidget({ id, value, onChange, invalid, describedBy }: WidgetProps) {
  return (
    <Switch id={id} checked={value === true} onCheckedChange={(checked) => onChange(checked)} aria-invalid={invalid} aria-describedby={describedBy} />
  )
}

/** An instant, edited in the viewer's local time and sent as an ISO-8601 UTC string. */
function InstantWidget({ id, value, onChange, invalid, describedBy, field }: WidgetProps) {
  return (
    <Input
      id={id}
      type="datetime-local"
      step={1}
      value={toLocalInput(value)}
      onChange={(e) => onChange(fromLocalInput(e.target.value))}
      aria-invalid={invalid}
      aria-describedby={describedBy}
      required={field.required}
      className="w-fit"
    />
  )
}

const none = "__basekt_none__"

function EnumWidget({ field, id, value, onChange, invalid, describedBy }: WidgetProps) {
  return (
    <Select value={value == null ? (field.required ? "" : none) : String(value)} onValueChange={(next) => onChange(next === none ? null : next)}>
      <SelectTrigger id={id} className="w-full sm:w-64" aria-invalid={invalid} aria-describedby={describedBy}>
        <SelectValue placeholder="Choose…" />
      </SelectTrigger>
      <SelectContent>
        {!field.required && <SelectItem value={none}>—</SelectItem>}
        {(field.values ?? []).map((choice) => (
          <SelectItem key={choice} value={choice}>{choice}</SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}

function ReferenceWidget(props: WidgetProps) {
  return <ReferencePicker {...props} />
}

function maxLength(field: AdminField): number | undefined {
  for (const rule of field.rules) if (rule.kind === "length" && rule.max != null) return rule.max
  return undefined
}

/** `2026-03-01T09:30:00Z` as `2026-03-01T10:30:00` in the viewer's zone, for a datetime-local input. */
export function toLocalInput(value: JsonValue | undefined): string {
  if (typeof value !== "string" || value === "") return ""
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ""
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 19)
}

/** A datetime-local input's value, in the viewer's zone, as an ISO-8601 UTC string, or null when empty. */
export function fromLocalInput(text: string): string | null {
  if (!text) return null
  const date = new Date(text)
  return Number.isNaN(date.getTime()) ? text : date.toISOString().replace(".000Z", "Z")
}
