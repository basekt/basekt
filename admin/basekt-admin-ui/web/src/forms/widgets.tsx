import { lazy, Suspense, useState, type ReactNode } from "react"
import type { AdminField, FieldType, JsonValue } from "@wire"
import { FileWidget } from "@/forms/file-widget"
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
  file: FileWidget,
}

/** The widget for a type this panel doesn't know: a text box, so the value can still be read and edited. */
export const fallbackWidget: Widget = TextWidget

/** The widget for [field]. */
export function widgetFor(field: AdminField): Widget {
  if (field.list) return ListWidget
  if (field.markdown) return MarkdownWidget
  return (widgets as Partial<Record<string, Widget>>)[field.type] ?? fallbackWidget
}

/** A list, edited as one item per line. Items of an integer field are sent as numbers once they parse. */
function ListWidget({ field, id, value, onChange, invalid, describedBy }: WidgetProps) {
  const [text, setText] = useState(Array.isArray(value) ? value.join("\n") : "")
  const numeric = field.type === "int" || field.type === "long"
  const change = (next: string) => {
    setText(next)
    const lines = next.split("\n").map((line) => line.trim()).filter((line) => line !== "")
    if (lines.length === 0) onChange(field.required ? [] : null)
    else onChange(lines.map((line) => (numeric && /^-?\d+$/.test(line) && Number.isSafeInteger(Number(line)) ? Number(line) : line)))
  }
  return (
    <textarea
      id={id}
      rows={4}
      value={text}
      onChange={(e) => change(e.target.value)}
      aria-invalid={invalid}
      aria-describedby={describedBy}
      placeholder="One per line"
      className="border-input placeholder:text-muted-foreground focus-visible:ring-ring/50 aria-invalid:border-destructive flex min-h-16 w-full rounded-md border bg-transparent px-3 py-2 text-sm shadow-xs outline-none focus-visible:ring-[3px]"
    />
  )
}

/** The visual editor is a separate chunk: it is large, and most forms have no Markdown field. */
const MarkdownEditor = lazy(() => import("@/forms/markdown-editor"))

/**
 * Markdown text, edited visually or as source. The visual editor draws headings, lists, tables and, where the field
 * allows them, formulas as they are typed; the source box shows the Markdown that is saved. Both read and write the
 * same text, so a person can switch at any time.
 */
function MarkdownWidget({ field, id, value, onChange, invalid, describedBy }: WidgetProps) {
  const options = field.markdown!
  const text = value == null ? "" : String(value)
  const [mode, setMode] = useState<"visual" | "source">("visual")
  // The visual editor keeps its own text once it starts, so it starts over after the source box changed the text.
  const [revision, setRevision] = useState(0)
  const set = (next: string) => onChange(next === "" && !field.required ? null : next)
  const formulas = options.math ? ", with formulas: $x^2$ inline or $$ ... $$ as a block" : ""
  const tab = (name: "visual" | "source", label: string) => (
    <button
      type="button"
      role="tab"
      aria-selected={mode === name}
      onClick={() => {
        if (name === "visual" && mode === "source") setRevision((n) => n + 1)
        setMode(name)
      }}
      className={`rounded-md px-2.5 py-1 text-xs font-medium ${mode === name ? "bg-muted text-foreground" : "text-muted-foreground hover:text-foreground"}`}
    >
      {label}
    </button>
  )
  return (
    <div className="space-y-1.5" id={id}>
      <div role="tablist" aria-label="How to edit the text" className="flex gap-1">
        {tab("visual", "Visual")}
        {tab("source", "Markdown")}
      </div>
      {mode === "visual" ? (
        <Suspense fallback={<p className="text-muted-foreground text-sm">Loading the editor...</p>}>
          <MarkdownEditor
            key={revision}
            value={text}
            math={options.math}
            invalid={invalid}
            describedBy={describedBy}
            onChange={set}
          />
        </Suspense>
      ) : (
        <textarea
          aria-label="Markdown source"
          rows={12}
          value={text}
          onChange={(e) => set(e.target.value)}
          aria-invalid={invalid}
          aria-describedby={describedBy}
          maxLength={options.maxLength}
          className="border-input placeholder:text-muted-foreground focus-visible:ring-ring/50 aria-invalid:border-destructive flex min-h-32 w-full rounded-md border bg-transparent px-3 py-2 font-mono text-sm shadow-xs outline-none focus-visible:ring-[3px]"
        />
      )}
      <p className="text-muted-foreground text-xs">{`Markdown${formulas}. ${text.length} of ${options.maxLength} characters.`}</p>
    </div>
  )
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
