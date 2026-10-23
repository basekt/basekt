import { useState } from "react"
import { ChevronLeft, Trash2 } from "lucide-react"
import { toast } from "sonner"
import type { AdminResource, JsonObject } from "@wire"
import { ActionButtons } from "@/components/action-button"
import { ErrorState } from "@/components/error-state"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
import { RecordForm } from "@/forms/record-form"
import { formatValue, humanize, recordId } from "@/lib/fields"
import { useAsync } from "@/lib/use-async"
import { can, usePanel } from "@/panel"
import { Link, useRouter } from "@/router"

/**
 * One record: an edit form when the caller may update, its fields read-only otherwise, a delete button when the
 * caller may delete, and links along its relations.
 */
export function RecordView({ resource, id }: { resource: AdminResource; id: string }) {
  const { api } = usePanel()
  const record = useAsync(() => api.get(resource.name, id), [api, resource.name, id])
  return (
    <div className="grid max-w-3xl gap-6">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <BackToList resource={resource} />
          <h1 className="text-2xl font-semibold tracking-tight">{id}</h1>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {record.data && <ActionButtons resource={resource} target="record" id={id} onDone={record.reload} />}
          {can(resource, "delete") && record.data && <DeleteButton resource={resource} id={id} />}
        </div>
      </div>
      {record.error ? (
        <ErrorState error={record.error} onRetry={record.reload} />
      ) : !record.data ? (
        <div className="grid gap-3">
          {resource.fields.map((field) => <Skeleton key={field.name} className="h-9 w-full" />)}
        </div>
      ) : can(resource, "update") ? (
        <RecordForm
          key={JSON.stringify(record.data)}
          resource={resource}
          mode="edit"
          recordKey={id}
          initial={record.data}
          onSaved={() => {
            toast.success(`Saved ${id}.`)
            record.reload()
          }}
        />
      ) : (
        <ReadOnly resource={resource} record={record.data} />
      )}
      {record.data && <Relations resource={resource} id={id} record={record.data} />}
    </div>
  )
}

/** A form for a new record, when the caller may create one. */
export function CreateView({ resource }: { resource: AdminResource }) {
  const { navigate } = useRouter()
  if (!can(resource, "create")) {
    return (
      <Alert>
        <AlertTitle>You can't create {humanize(resource.name).toLowerCase()}</AlertTitle>
        <AlertDescription>Your roles allow {resource.operations.join(", ")} only.</AlertDescription>
      </Alert>
    )
  }
  const toList = () => navigate({ screen: "list", resource: resource.name, search: new URLSearchParams() })
  return (
    <div className="grid max-w-3xl gap-6">
      <div>
        <BackToList resource={resource} />
        <h1 className="text-2xl font-semibold tracking-tight">New {humanize(resource.name).toLowerCase()}</h1>
      </div>
      <RecordForm
        resource={resource}
        mode="create"
        initial={{}}
        onCancel={toList}
        onSaved={(saved) => {
          toast.success(`Created ${recordId(saved)}.`)
          navigate({ screen: "detail", resource: resource.name, id: recordId(saved) }, { replace: true })
        }}
      />
    </div>
  )
}

function BackToList({ resource }: { resource: AdminResource }) {
  return (
    <Button variant="ghost" size="sm" asChild className="-ml-2">
      <Link to={{ screen: "list", resource: resource.name, search: new URLSearchParams() }}>
        <ChevronLeft /> {humanize(resource.name)}
      </Link>
    </Button>
  )
}

function ReadOnly({ resource, record }: { resource: AdminResource; record: JsonObject }) {
  return (
    <dl className="grid gap-x-6 gap-y-3 rounded-md border p-4 sm:grid-cols-[12rem_1fr]">
      {resource.fields.map((field) => (
        <div key={field.name} className="contents">
          <dt className="text-sm text-muted-foreground">{humanize(field.name)}</dt>
          <dd>{formatValue(field, record[field.name])}</dd>
        </div>
      ))}
    </dl>
  )
}

function DeleteButton({ resource, id }: { resource: AdminResource; id: string }) {
  const { api } = usePanel()
  const { navigate } = useRouter()
  const [failure, setFailure] = useState<unknown>(null)
  const remove = async () => {
    try {
      await api.remove(resource.name, id)
      toast.success(`Deleted ${id}.`)
      navigate({ screen: "list", resource: resource.name, search: new URLSearchParams() }, { replace: true })
    } catch (error) {
      setFailure(error)
    }
  }
  return (
    <>
      <AlertDialog>
        <AlertDialogTrigger asChild>
          <Button variant="outline">
            <Trash2 /> Delete
          </Button>
        </AlertDialogTrigger>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete {id}?</AlertDialogTitle>
            <AlertDialogDescription>This removes the record from {humanize(resource.name).toLowerCase()}. It can't be undone.</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={remove} className="bg-destructive text-white hover:bg-destructive/90">Delete</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
      {failure != null && (
        <div className="basis-full">
          <ErrorState error={failure} />
        </div>
      )}
    </>
  )
}

function Relations({ resource, id, record }: { resource: AdminResource; id: string; record: JsonObject }) {
  const links = resource.relations.filter((relation) => relation.kind === "hasMany" || record[relation.field] != null)
  if (links.length === 0) return null
  return (
    <div className="grid gap-2">
      <h2 className="text-sm font-medium text-muted-foreground">Related</h2>
      <div className="flex flex-wrap gap-2">
        {links.map((relation) => (
          <Button key={relation.name} variant="outline" size="sm" asChild>
            <Link
              to={relation.kind === "hasMany"
                ? { screen: "list", resource: relation.target, search: new URLSearchParams({ [relation.field]: id }) }
                : { screen: "detail", resource: relation.target, id: String(record[relation.field]) }}
            >
              {humanize(relation.name)}
            </Link>
          </Button>
        ))}
      </div>
    </div>
  )
}
