import { useState, type FormEvent } from "react"
import { CircleAlert } from "lucide-react"
import type { AdminField, AdminResource, FieldError, JsonObject, JsonValue } from "@wire"
import { ApiFailure } from "@/api/client"
import { ErrorState } from "@/components/error-state"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import { widgetFor } from "@/forms/widgets"
import { humanize } from "@/lib/fields"
import { usePanel } from "@/panel"

/** Errors from a failed save, split into those a field shows and those the form shows above its fields. */
export interface FormErrors {
  fields: Record<string, FieldError[]>
  form: FieldError[]
  message: string | null
}

const noErrors: FormErrors = { fields: {}, form: [], message: null }

/** Spreads an API error over the form: each field error under its field, the rest above the form. */
export function errorsFrom(resource: AdminResource, failure: ApiFailure): FormErrors {
  const names = new Set(resource.fields.map((f) => f.name))
  const fields: Record<string, FieldError[]> = {}
  const form: FieldError[] = []
  for (const error of failure.error?.fields ?? []) {
    if (names.has(error.field)) (fields[error.field] ??= []).push(error)
    else form.push(error)
  }
  return { fields, form, message: failure.message }
}

/**
 * A form for every field of [resource], with each field's widget from the registry. In `create` mode it sends the
 * fields that have a value; in `edit` mode it sends only what changed. Validation errors and hook vetoes come back
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
  return (
    <form onSubmit={submit} noValidate aria-label={`${mode === "create" ? "New" : "Edit"} ${humanize(resource.name)}`} className="grid gap-5">
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
      {resource.fields.filter((field) => field.auto == null).map((field) => {
        const id = `field-${field.name}`
        const fieldErrors = errors.fields[field.name] ?? []
        const Widget = widgetFor(field)
        return (
          <div key={field.name} className="grid gap-2">
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
      })}
      <div className="flex gap-2">
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
