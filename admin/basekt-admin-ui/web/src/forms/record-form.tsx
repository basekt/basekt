import { useState, type FormEvent } from "react"
import { CircleAlert, Plus } from "lucide-react"
import type { AdminField, AdminResource, FieldError, JsonObject, JsonValue } from "@wire"
import { ApiFailure } from "@/api/client"
import { ErrorState } from "@/components/error-state"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import { widgetFor } from "@/forms/widgets"
import { humanize, itemNoun } from "@/lib/fields"
import { cn } from "@/lib/utils"
import { usePanel } from "@/panel"

/** Errors from a failed save, split into those a field shows and those the form shows above its fields. */
export interface FormErrors {
  fields: Record<string, FieldError[]>
  form: FieldError[]
  message: string | null
}

const noErrors: FormErrors = { fields: {}, form: [], message: null }

/**
 * Spreads an API error over the form: each field error under its field, the rest above the form. An error about an
 * item of a list (`options.1.text`) goes under the list, and says which item and field it is about.
 */
export function errorsFrom(resource: AdminResource, failure: ApiFailure): FormErrors {
  const all = [...resource.fields, ...(idField(resource) ? [idField(resource)!] : [])]
  const byName = new Map(all.map((f) => [f.name, f]))
  const fields: Record<string, FieldError[]> = {}
  const form: FieldError[] = []
  for (const error of failure.error?.fields ?? []) {
    const [root, ...path] = error.field.split(".")
    const field = byName.get(root)
    if (!field) form.push(error)
    else (fields[root] ??= []).push(path.length === 0 ? error : { ...error, message: `${itemPlace(field, path)}: ${error.message}` })
  }
  return { fields, form, message: failure.message }
}

/** Where in a list an error is: "Option 2" or "Option 2, text". */
function itemPlace(field: AdminField, path: string[]): string {
  const [index, ...rest] = path
  const item = /^\d+$/.test(index) ? `${humanize(itemNoun(field))} ${Number(index) + 1}` : humanize(index)
  return rest.length === 0 ? item : `${item}, ${rest.map(humanize).join(", ").toLowerCase()}`
}

/**
 * The id a create form asks for, as a field of its own, when the client chooses ids: a required string checked by
 * the resource's id rules. `null` when the server or the data source makes them.
 */
export function idField(resource: AdminResource): AdminField | null {
  if (resource.id?.source !== "natural") return null
  return { name: "id", type: "string", required: true, values: null, target: null, rules: resource.id.rules, bucket: null, scopedValues: null, auto: null, list: false, markdown: null, fields: null, itemIds: false }
}

/**
 * A form for every field of [resource], with each field's widget from the registry, and the id first when a create
 * must choose one. In `create` mode it sends the fields that have a value; in `edit` mode it sends only what changed. Validation errors and hook vetoes come back
 * from the API and are shown under the fields they name.
 */
export function RecordForm({ resource, mode, initial, recordKey, onSaved, onCancel }: {
  resource: AdminResource
  mode: "create" | "edit"
  initial: JsonObject
  recordKey?: string
  onSaved: (record: JsonObject) => void
  onCancel?: () => void
}) {
  const { api } = usePanel()
  const [values, setValues] = useState<JsonObject>(initial)
  const [errors, setErrors] = useState<FormErrors>(noErrors)
  const [failure, setFailure] = useState<unknown>(null)
  const [saving, setSaving] = useState(false)
  const [opened, setOpened] = useState<Set<string>>(() => new Set())

  const set = (field: AdminField, value: JsonValue) => {
    setValues((current) => ({ ...current, [field.name]: value }))
    if (errors.fields[field.name]) setErrors((current) => ({ ...current, fields: { ...current.fields, [field.name]: [] } }))
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setSaving(true)
    setFailure(null)
    try {
      const saved = mode === "create"
        ? await api.create(resource.name, present(values))
        : await api.update(resource.name, recordKey!, changes(resource, initial, values))
      setErrors(noErrors)
      onSaved(saved)
    } catch (error) {
      if (error instanceof ApiFailure && error.error && !error.unauthorized && !error.forbidden) setErrors(errorsFrom(resource, error))
      else setFailure(error)
    } finally {
      setSaving(false)
    }
  }

  const fieldErrorCount = Object.values(errors.fields).reduce((n, list) => n + list.length, 0)
  const chosenId = mode === "create" ? idField(resource) : null
  const fields = [...(chosenId ? [chosenId] : []), ...resource.fields.filter((field) => field.auto == null)]
  const aside = new Set(resource.view?.aside ?? [])
  const folded = new Set(resource.view?.folded ?? [])
  const main = fields.filter((field) => !aside.has(field.name))
  const side = fields.filter((field) => aside.has(field.name))
  // A folded field opens when the viewer asks, and is open already when it has a value or an error.
  const isOpen = (field: AdminField) => !folded.has(field.name) || opened.has(field.name) || hasValue(initial[field.name]) || (errors.fields[field.name]?.length ?? 0) > 0

  const fieldBlock = (field: AdminField) => {
    const id = `field-${field.name}`
    if (!isOpen(field)) {
      return (
        <div key={field.name}>
          <Button type="button" variant="ghost" size="sm" className="-ml-2 text-muted-foreground" onClick={() => setOpened((current) => new Set(current).add(field.name))}>
            <Plus /> Add {humanize(field.name).toLowerCase()}
          </Button>
        </div>
      )
    }
    const fieldErrors = errors.fields[field.name] ?? []
    const Widget = widgetFor(field)
    return (
      <div key={field.name} className="grid min-w-0 grid-cols-1 gap-2">
        <Label htmlFor={id}>
          {humanize(field.name)}
          {field.required && <span className="text-destructive" aria-hidden>*</span>}
        </Label>
        <Widget
          field={field}
          id={id}
          value={values[field.name]}
          onChange={(value) => set(field, value)}
          invalid={fieldErrors.length > 0}
          describedBy={fieldErrors.length > 0 ? `${id}-error` : undefined}
        />
        {fieldErrors.length > 0 && (
          <p id={`${id}-error`} className="text-sm text-destructive">
            {fieldErrors.map((e) => e.message).join(" ")}
          </p>
        )}
      </div>
    )
  }

  return (
    <form onSubmit={submit} noValidate aria-label={`${mode === "create" ? "New" : "Edit"} ${humanize(resource.name)}`} className="@container grid gap-5">
      {failure != null && <ErrorState error={failure} />}
      {errors.message && (
        <Alert variant="destructive">
          <CircleAlert />
          <AlertTitle>{errors.message}</AlertTitle>
          {(errors.form.length > 0 || fieldErrorCount > 0) && (
            <AlertDescription>
              {errors.form.map((e) => (
                <p key={`${e.field}:${e.code}`}>{e.field ? `${humanize(e.field)}: ` : ""}{e.message}</p>
              ))}
              {fieldErrorCount > 0 && <p>Check the highlighted fields.</p>}
            </AlertDescription>
          )}
        </Alert>
      )}
      {/* On a wide form the aside fields sit in a column of their own, so the text being written keeps the width. */}
      <div className={cn("grid gap-5", side.length > 0 && "@3xl:grid-cols-[minmax(0,1fr)_18rem] @3xl:items-start")}>
        <div className="grid min-w-0 grid-cols-1 gap-5">{main.map(fieldBlock)}</div>
        {side.length > 0 && (
          <div data-slot="form-aside" className="grid min-w-0 grid-cols-1 gap-5 @3xl:sticky @3xl:top-4 @3xl:rounded-md @3xl:border @3xl:p-4">
            {side.map(fieldBlock)}
          </div>
        )}
      </div>
      {/* Save stays in reach at the bottom of the screen, however long the form. */}
      <div className="bg-background/95 sticky bottom-0 z-10 -mx-1 flex gap-2 border-t px-1 py-3 backdrop-blur">
        <Button type="submit" disabled={saving}>
          {mode === "create" ? "Create" : "Save"}
        </Button>
        {onCancel && (
          <Button type="button" variant="ghost" onClick={onCancel}>
            Cancel
          </Button>
        )}
      </div>
    </form>
  )
}

/** Whether [value] is something a person entered: not empty, and not an empty list. */
function hasValue(value: JsonValue | undefined): boolean {
  return value !== null && value !== undefined && value !== "" && !(Array.isArray(value) && value.length === 0)
}

/** The fields that have a value: a create leaves the rest to the server's defaults. */
function present(values: JsonObject): JsonObject {
  return Object.fromEntries(Object.entries(values).filter(([, value]) => value !== null && value !== undefined && value !== ""))
}

/** The fields whose value differs from the record as it was loaded. */
function changes(resource: AdminResource, initial: JsonObject, values: JsonObject): JsonObject {
  const changed: JsonObject = {}
  for (const field of resource.fields) {
    const before = initial[field.name] ?? null
    const after = values[field.name] ?? null
    if (JSON.stringify(before) !== JSON.stringify(after)) changed[field.name] = after
  }
  // The version the form was opened at, so the server refuses the save if someone changed the record since.
  const version = resource.fields.find((field) => field.auto?.kind === "version")
  if (version && initial[version.name] != null) changed[version.name] = initial[version.name]
  return changed
}
