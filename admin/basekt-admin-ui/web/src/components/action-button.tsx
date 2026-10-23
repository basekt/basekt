import { useState, type FormEvent } from "react"
import { Play } from "lucide-react"
import { toast } from "sonner"
import type { AdminAction, AdminResource, JsonObject } from "@wire"
import { ApiFailure } from "@/api/client"
import { ErrorState } from "@/components/error-state"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog"
import { Label } from "@/components/ui/label"
import { errorsFrom, type FormErrors } from "@/forms/record-form"
import { widgetFor } from "@/forms/widgets"
import { humanize } from "@/lib/fields"
import { usePanel } from "@/panel"

const noErrors: FormErrors = { fields: {}, form: [], message: null }

/**
 * A button that runs [action] on the record [id], or on the whole resource without one. An action with input or a
 * confirmation question opens a dialog first; the input's errors come back under its fields.
 */
export function ActionButton({ resource, action, id, onDone }: {
  resource: AdminResource
  action: AdminAction
  id?: string
  onDone: () => void
}) {
  const { api } = usePanel()
  const [open, setOpen] = useState(false)
  const [values, setValues] = useState<JsonObject>({})
  const [errors, setErrors] = useState<FormErrors>(noErrors)
  const [failure, setFailure] = useState<unknown>(null)
  const [running, setRunning] = useState(false)
  // The input is checked like a record, so its errors spread over its fields the same way.
  const form: AdminResource = { ...resource, fields: action.input }
  const label = humanize(action.name)

  const run = async () => {
    setRunning(true)
    setFailure(null)
    try {
      const input = Object.fromEntries(Object.entries(values).filter(([, value]) => value != null && value !== ""))
      await api.runAction(resource.name, action.name, id, input)
      toast.success(`${label}: done.`)
      setErrors(noErrors)
      setValues({})
      setOpen(false)
      onDone()
    } catch (error) {
      if (error instanceof ApiFailure && error.error && !error.unauthorized && !error.forbidden) {
        setErrors(errorsFrom(form, error))
        if (!open) toast.error(error.message)
      } else if (open) {
        setFailure(error)
      } else {
        toast.error(error instanceof Error ? error.message : `${label} failed.`)
      }
    } finally {
      setRunning(false)
    }
  }

  if (action.input.length === 0 && action.confirm == null) {
    return (
      <Button variant="outline" onClick={run} disabled={running}>
        <Play /> {label}
      </Button>
    )
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    void run()
  }
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button variant="outline">
          <Play /> {label}
        </Button>
      </DialogTrigger>
      <DialogContent>
        <form onSubmit={submit} noValidate aria-label={label} className="grid gap-4">
          <DialogHeader>
            <DialogTitle>{label}</DialogTitle>
            {action.confirm && <DialogDescription>{action.confirm}</DialogDescription>}
          </DialogHeader>
          {errors.message && (
            <Alert variant="destructive">
              <AlertDescription>
                <p>{errors.message}</p>
                {errors.form.map((e) => <p key={`${e.field}:${e.code}`}>{e.message}</p>)}
              </AlertDescription>
            </Alert>
          )}
          {action.input.map((field) => {
            const fieldId = `action-${action.name}-${field.name}`
            const fieldErrors = errors.fields[field.name] ?? []
            const Widget = widgetFor(field)
            return (
              <div key={field.name} className="grid gap-2">
                <Label htmlFor={fieldId}>
                  {humanize(field.name)}
                  {field.required && <span className="text-destructive" aria-hidden>*</span>}
                </Label>
                <Widget
                  field={field}
                  id={fieldId}
                  value={values[field.name] ?? null}
                  onChange={(value) => setValues((current) => ({ ...current, [field.name]: value }))}
                  invalid={fieldErrors.length > 0}
                  describedBy={fieldErrors.length > 0 ? `${fieldId}-error` : undefined}
                />
                {fieldErrors.length > 0 && (
                  <p id={`${fieldId}-error`} className="text-sm text-destructive">{fieldErrors.map((e) => e.message).join(" ")}</p>
                )}
              </div>
            )
          })}
          {failure != null && <ErrorState error={failure} />}
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={() => setOpen(false)}>Cancel</Button>
            <Button type="submit" disabled={running}>{label}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/** The actions of [resource] that run on [target], as buttons. */
export function ActionButtons({ resource, target, id, onDone }: {
  resource: AdminResource
  target: AdminAction["target"]
  id?: string
  onDone: () => void
}) {
  const actions = (resource.actions ?? []).filter((action) => action.target === target)
  if (actions.length === 0) return null
  return (
    <>
      {actions.map((action) => <ActionButton key={action.name} resource={resource} action={action} id={id} onDone={onDone} />)}
    </>
  )
}
