import { useState } from "react"
import { ChevronLeft, ExternalLink, Trash2, X } from "lucide-react"
import { toast } from "sonner"
import type { AdminField, AdminResource, JsonObject, JsonValue } from "@wire"
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
import { recordLinks } from "@/lib/links"
import { useAsync } from "@/lib/use-async"
import { cn } from "@/lib/utils"
import { can, usePanel } from "@/panel"
import { Link, useRouter } from "@/router"

/**
 * One record: an edit form when the caller may update, its fields read-only otherwise, a delete button when the
 * caller may delete, and links along its relations.
 */
export function RecordView({ resource, id }: { resource: AdminResource; id: string }) {
  const { api } = usePanel()
  const { navigate } = useRouter()
  const record = useAsync(() => api.get(resource.name, id), [api, resource.name, id])
  return (
    <div className={cn("grid gap-6", pageWidth(resource))}>
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <BackToList resource={resource} />
          <h1 className="text-2xl font-semibold tracking-tight">{id}</h1>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {record.data && <SiteLinks resource={resource} record={record.data} />}
          {record.data && <ActionButtons resource={resource} target="record" id={id} record={record.data} onDone={record.reload} />}
          {can(resource, "delete") && record.data && (
            <DeleteButton
              resource={resource}
              id={id}
              onDeleted={() => navigate({ screen: "list", resource: resource.name, search: new URLSearchParams() }, { replace: true })}
            />
          )}
        </div>
      </div>
      <RecordBody resource={resource} id={id} record={record} />
    </div>
  )
}

/**
 * An inline inspector for one record inside a split-view or slide-over sheet on the list screen.
 */
export function RecordInspector({
  resource,
  id,
  onClose,
  onChanged,
  onDeleted,
}: {
  resource: AdminResource
  id: string
  onClose: () => void
  onChanged?: () => void
  onDeleted?: () => void
}) {
  const { api } = usePanel()
  const record = useAsync(() => api.get(resource.name, id), [api, resource.name, id])
  return (
    <div className="grid gap-4">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{humanize(resource.name)}</p>
          <h2 className="truncate font-mono text-lg font-semibold tracking-tight">{id}</h2>
        </div>
        <div className="flex shrink-0 items-center gap-1">
          <Button variant="ghost" size="sm" asChild>
            <Link to={{ screen: "detail", resource: resource.name, id }}>Open page</Link>
          </Button>
          <Button type="button" variant="ghost" size="icon-sm" aria-label="Close inspector" onClick={onClose}>
            <X />
          </Button>
        </div>
      </div>
      {record.data && (
        <div className="flex flex-wrap items-center gap-2">
          <SiteLinks resource={resource} record={record.data} />
          <ActionButtons
            resource={resource}
            target="record"
            id={id}
            record={record.data}
            onDone={() => {
              record.reload()
              onChanged?.()
            }}
          />
          {can(resource, "delete") && (
            <DeleteButton
              resource={resource}
              id={id}
              onDeleted={() => {
                onDeleted?.()
                onClose()
              }}
            />
          )}
        </div>
      )}
      <RecordBody resource={resource} id={id} record={record} onSaved={onChanged} />
    </div>
  )
}

function RecordBody({
  resource,
  id,
  record,
  onSaved,
}: {
  resource: AdminResource
  id: string
  record: ReturnType<typeof useAsync<JsonObject>>
  onSaved?: () => void
}) {
  return (
    <>
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
            onSaved?.()
          }}
        />
      ) : (
        <ReadOnly resource={resource} record={record.data} />
      )}
      {record.data && <Relations resource={resource} id={id} record={record.data} />}
    </>
  )
}

/** A form for a new record, when the caller may create one. */
export function CreateView({ resource }: { resource: AdminResource }) {
  const { navigate } = useRouter()
  if (!can(resource, "create")) {
    return (
      <Alert>
        <AlertTitle>You can't create {humanize(resource.name).toLowerCase()}</AlertTitle>
        <AlertDescription>
          {resource.readOnly
            ? "Its records come from the application, so they can't be created, edited or deleted here."
            : `Your roles allow ${resource.operations.join(", ")} only.`}
        </AlertDescription>
      </Alert>
    )
  }
  const toList = () => navigate({ screen: "list", resource: resource.name, search: new URLSearchParams() })
  return (
    <div className={cn("grid gap-6", pageWidth(resource))}>
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

/** The resource's links to the host app's pages for this record, each opening in a new tab. */
function SiteLinks({ resource, record }: { resource: AdminResource; record: JsonObject }) {
  return (
    <>
      {recordLinks(resource.links, record).map((link) => (
        <Button key={link.label} variant="outline" size="sm" asChild>
          <a href={link.href} target="_blank" rel="noopener noreferrer">
            <ExternalLink /> {link.label}
          </a>
        </Button>
      ))}
    </>
  )
}

/** A form with a side column needs the room for both; a form of one column reads best narrow. */
function pageWidth(resource: AdminResource): string {
  return (resource.view?.aside?.length ?? 0) > 0 ? "max-w-6xl" : "max-w-3xl"
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
  const { api } = usePanel()
  const references = resource.fields.filter((field) => field.type === "reference" && field.target && record[field.name] != null)
  // A reference list points at several records; each one is named.
  const idsOf = (value: JsonValue | undefined): string[] => (Array.isArray(value) ? value.map(String) : value == null ? [] : [String(value)])
  const wanted = references.map((field) => `${field.target}/${idsOf(record[field.name]).join("|")}`).join(",")
  // The records this one points to, named by their labels: one call per target resource.
  const labels = useAsync(async () => {
    const ids = new Map<string, Set<string>>()
    for (const field of references) {
      const target = field.target as string
      for (const one of idsOf(record[field.name])) ids.set(target, (ids.get(target) ?? new Set()).add(one))
    }
    const named: Record<string, Record<string, string>> = {}
    await Promise.all(
      [...ids].map(async ([target, set]) => {
        named[target] = (await api.labels(target, [...set]).catch(() => ({ labels: {} }))).labels
      }),
    )
    return named
  }, [api, wanted])

  return (
    <dl className="grid gap-x-6 gap-y-3 rounded-md border p-4 sm:grid-cols-[12rem_1fr]">
      {resource.fields.map((field) => {
        const value = record[field.name]
        return (
          <div key={field.name} className="contents">
            <dt className="text-sm text-muted-foreground">{humanize(field.name)}</dt>
            <dd>
              {field.type === "reference" && field.target && value != null ? (
                idsOf(value).map((one, i) => (
                  <span key={one}>
                    {i > 0 && ", "}
                    <Link to={{ screen: "detail", resource: field.target!, id: one }} title={one} className="underline decoration-muted-foreground/40 underline-offset-4 hover:decoration-foreground">
                      {labels.data?.[field.target!]?.[one] ?? one}
                    </Link>
                  </span>
                ))
              ) : field.type === "object" && Array.isArray(value) && value.length > 0 ? (
                <ol className="list-decimal space-y-1 pl-5">
                  {value.map((item, i) => (
                    <li key={i}>{itemSummary(field, item)}</li>
                  ))}
                </ol>
              ) : (
                formatValue(field, value)
              )}
            </dd>
          </div>
        )
      })}
    </dl>
  )
}

/** An object list's item as one line: each set field's name and value. */
function itemSummary(field: AdminField, item: JsonValue): string {
  if (item === null || typeof item !== "object" || Array.isArray(item)) return formatValue(undefined, item)
  return (field.fields ?? [])
    .filter((sub) => item[sub.name] != null && item[sub.name] !== "")
    .map((sub) => (sub.type === "bool" ? (item[sub.name] ? humanize(sub.name) : null) : `${humanize(sub.name)}: ${formatValue(sub, item[sub.name])}`))
    .filter((part) => part !== null)
    .join(" · ")
}

function DeleteButton({ resource, id, onDeleted }: { resource: AdminResource; id: string; onDeleted: () => void }) {
  const { api } = usePanel()
  const [failure, setFailure] = useState<unknown>(null)
  const remove = async () => {
    try {
      await api.remove(resource.name, id)
      toast.success(`Deleted ${id}.`)
      onDeleted()
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
