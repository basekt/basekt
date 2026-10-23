import { ChevronLeft } from "lucide-react"
import type { AdminResource } from "@wire"
import { ErrorState } from "@/components/error-state"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
import { formatValue, humanize } from "@/lib/fields"
import { useAsync } from "@/lib/use-async"
import { usePanel } from "@/panel"
import { Link } from "@/router"

/** One record's fields, read-only, with links along its relations. */
export function RecordView({ resource, id }: { resource: AdminResource; id: string }) {
  const { api } = usePanel()
  const record = useAsync(() => api.get(resource.name, id), [api, resource.name, id])
  return (
    <div className="grid max-w-3xl gap-4">
      <div>
        <Button variant="ghost" size="sm" asChild className="-ml-2">
          <Link to={{ screen: "list", resource: resource.name, search: new URLSearchParams() }}>
            <ChevronLeft /> {humanize(resource.name)}
          </Link>
        </Button>
        <h1 className="text-2xl font-semibold tracking-tight">{id}</h1>
      </div>
      {record.error ? (
        <ErrorState error={record.error} onRetry={record.reload} />
      ) : (
        <dl className="grid gap-x-6 gap-y-3 rounded-md border p-4 sm:grid-cols-[12rem_1fr]">
          {resource.fields.map((field) => (
            <div key={field.name} className="contents">
              <dt className="text-sm text-muted-foreground">{humanize(field.name)}</dt>
              <dd>{record.data ? formatValue(field, record.data[field.name]) : <Skeleton className="h-4 w-32" />}</dd>
            </div>
          ))}
        </dl>
      )}
      {resource.relations.length > 0 && record.data && (
        <div className="flex flex-wrap gap-2">
          {resource.relations.map((relation) =>
            relation.kind === "hasMany" ? (
              <Button key={relation.name} variant="outline" size="sm" asChild>
                <Link to={{ screen: "list", resource: relation.target, search: new URLSearchParams({ [relation.field]: id }) }}>
                  {humanize(relation.name)}
                </Link>
              </Button>
            ) : record.data![relation.field] != null ? (
              <Button key={relation.name} variant="outline" size="sm" asChild>
                <Link to={{ screen: "detail", resource: relation.target, id: String(record.data![relation.field]) }}>
                  {humanize(relation.name)}
                </Link>
              </Button>
            ) : null,
          )}
        </div>
      )}
    </div>
  )
}
