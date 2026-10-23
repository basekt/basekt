import { useState, type FormEvent } from "react"
import { ArrowDown, ArrowUp, ArrowUpDown, ChevronLeft, ChevronRight, Plus, Search, X } from "lucide-react"
import { BulkActions, BulkFailures, ExportButton } from "@/components/bulk-actions"
import type { AdminField, AdminResource, BulkFailure, JsonObject } from "@wire"
import { ErrorState } from "@/components/error-state"
import { FilterBar } from "@/components/filter-bar"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import { Skeleton } from "@/components/ui/skeleton"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { formatValue, humanize, ID, recordId } from "@/lib/fields"
import { useAsync } from "@/lib/use-async"
import { can, usePanel } from "@/panel"
import { Link, useRouter } from "@/router"

/** One page's place in the list: the cursor that fetches it and the row number it starts at. */
interface PageMark {
  cursor: string | null
  start: number
}

/**
 * A resource's records: columns from the schema, search, filters and sort kept in the URL, and cursor pagination
 * with the total. Buttons for what the caller may not do are left out.
 */
export function ListView({ resource, search }: { resource: AdminResource; search: URLSearchParams }) {
  // A new search, sort or filter starts again from the first page.
  return <ListPages key={`${resource.name}?${search.toString()}`} resource={resource} search={search} />
}

function ListPages({ resource, search }: { resource: AdminResource; search: URLSearchParams }) {
  const { api } = usePanel()
  const { navigate } = useRouter()
  const [pages, setPages] = useState<PageMark[]>([{ cursor: null, start: 0 }])
  const mark = pages[pages.length - 1]
  const params = Object.fromEntries(search)
  const page = useAsync(
    () => api.list(resource.name, mark.cursor ? { ...params, cursor: mark.cursor } : params),
    [api, resource.name, search.toString(), mark.cursor],
  )

  const setSearch = (next: URLSearchParams) => navigate({ screen: "list", resource: resource.name, search: next }, { replace: true })
  const fields: (AdminField | { name: string; type: "id" })[] = [{ name: ID, type: "id" }, ...resource.fields]
  const items = page.data?.items ?? []
  // Selection is per page: moving to another page or reloading starts with none.
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [bulkFailed, setBulkFailed] = useState<BulkFailure[]>([])
  const selectable = can(resource, "delete")
  const pageIds = items.map(recordId)
  const allSelected = pageIds.length > 0 && pageIds.every((id) => selected.has(id))
  const toggle = (id: string, on: boolean) =>
    setSelected((current) => {
      const next = new Set(current)
      if (on) next.add(id)
      else next.delete(id)
      return next
    })
  const columns = fields.length + (selectable ? 1 : 0)
  const afterBulkDelete = (failed: BulkFailure[]) => {
    setBulkFailed(failed)
    setSelected(new Set(failed.map((f) => f.id)))
    page.reload()
  }

  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">{humanize(resource.name)}</h1>
        <div className="flex items-center gap-2">
          <ExportButton resource={resource} params={params} />
          {can(resource, "create") && (
            <Button asChild>
              <Link to={{ screen: "create", resource: resource.name }}>
                <Plus /> New
              </Link>
            </Button>
          )}
        </div>
      </div>

      <div className="flex flex-wrap items-start gap-2">
        {resource.searchable.length > 0 && <SearchBox search={search} setSearch={setSearch} />}
        <FilterBar resource={resource} search={search} setSearch={setSearch} />
      </div>

      {selectable && selected.size > 0 && (
        <BulkActions resource={resource} ids={[...selected]} onClear={() => setSelected(new Set())} onDone={afterBulkDelete} />
      )}
      {bulkFailed.length > 0 && <BulkFailures failed={bulkFailed} onDismiss={() => setBulkFailed([])} />}

      {page.error ? (
        <ErrorState error={page.error} onRetry={page.reload} />
      ) : (
        <div className="overflow-x-auto rounded-md border">
          <Table>
            <TableHeader>
              <TableRow className="bg-muted/50">
                {selectable && (
                  <TableHead className="w-10">
                    <Checkbox
                      aria-label="Select all on this page"
                      checked={allSelected ? true : selected.size > 0 ? "indeterminate" : false}
                      disabled={pageIds.length === 0}
                      onCheckedChange={(on) => setSelected(on === true ? new Set(pageIds) : new Set())}
                    />
                  </TableHead>
                )}
                {fields.map((field) => (
                  <TableHead key={field.name} aria-sort={ariaSort(search, field.name)}>
                    <SortHeader resource={resource} field={field.name} search={search} setSearch={setSearch} />
                  </TableHead>
                ))}
              </TableRow>
            </TableHeader>
            <TableBody>
              {page.loading && !page.data ? (
                [0, 1, 2].map((row) => (
                  <TableRow key={row}>
                    {selectable && <TableCell />}
                    {fields.map((field) => (
                      <TableCell key={field.name}>
                        <Skeleton className="h-4 w-20" />
                      </TableCell>
                    ))}
                  </TableRow>
                ))
              ) : items.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={columns} className="h-24 text-center text-muted-foreground">
                    No records{search.size > 0 ? " match" : " yet"}.
                  </TableCell>
                </TableRow>
              ) : (
                items.map((record) => (
                  <RecordRow
                    key={recordId(record)}
                    resource={resource}
                    record={record}
                    selected={selectable ? selected.has(recordId(record)) : undefined}
                    onSelect={(on) => toggle(recordId(record), on)}
                  />
                ))
              )}
            </TableBody>
          </Table>
        </div>
      )}

      {page.data && (
        <div className="flex items-center justify-between gap-2 text-sm text-muted-foreground">
          <span aria-live="polite">
            {items.length === 0
              ? page.data.total === 0 ? "0 records" : ""
              : `${mark.start + 1}–${mark.start + items.length}${page.data.total != null ? ` of ${page.data.total}` : ""}`}
          </span>
          <div className="flex gap-2">
            <Button variant="outline" size="sm" disabled={pages.length === 1} onClick={() => { setSelected(new Set()); setPages(pages.slice(0, -1)) }}>
              <ChevronLeft /> Previous
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={!page.data.hasNext || !page.data.nextCursor}
              onClick={() => { setSelected(new Set()); setPages([...pages, { cursor: page.data!.nextCursor, start: mark.start + items.length }]) }}
            >
              Next <ChevronRight />
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}

/** A record's row; with [selected] set, it starts with a checkbox for bulk actions. */
function RecordRow({ resource, record, selected, onSelect }: {
  resource: AdminResource
  record: JsonObject
  selected?: boolean
  onSelect: (on: boolean) => void
}) {
  const id = recordId(record)
  return (
    <TableRow data-state={selected ? "selected" : undefined}>
      {selected !== undefined && (
        <TableCell>
          <Checkbox aria-label={`Select ${id}`} checked={selected} onCheckedChange={(on) => onSelect(on === true)} />
        </TableCell>
      )}
      <TableCell className="font-mono text-xs">
        <Link to={{ screen: "detail", resource: resource.name, id }} className="underline decoration-muted-foreground/40 underline-offset-4 hover:decoration-foreground">
          {id}
        </Link>
      </TableCell>
      {resource.fields.map((field) => (
        <TableCell key={field.name} className="max-w-64 truncate">
          <Cell field={field} record={record} />
        </TableCell>
      ))}
    </TableRow>
  )
}

function Cell({ field, record }: { field: AdminField; record: JsonObject }) {
  const value = record[field.name]
  if (value === null || value === undefined) return <span className="text-muted-foreground">—</span>
  if (field.type === "enum") return <Badge variant="outline">{String(value)}</Badge>
  if (field.type === "reference" && field.target) {
    return (
      <Link to={{ screen: "detail", resource: field.target, id: String(value) }} className="underline decoration-muted-foreground/40 underline-offset-4 hover:decoration-foreground">
        {String(value)}
      </Link>
    )
  }
  return <>{formatValue(field, value)}</>
}

function SearchBox({ search, setSearch }: { search: URLSearchParams; setSearch: (next: URLSearchParams) => void }) {
  const [text, setText] = useState(search.get("search") ?? "")
  const submit = (event: FormEvent) => {
    event.preventDefault()
    const next = new URLSearchParams(search)
    if (text.trim()) next.set("search", text.trim())
    else next.delete("search")
    setSearch(next)
  }
  return (
    <form onSubmit={submit} role="search" className="relative w-full sm:w-72">
      <Search className="pointer-events-none absolute top-2.5 left-2.5 size-4 text-muted-foreground" />
      <Input type="search" aria-label="Search" placeholder="Search…" value={text} onChange={(e) => setText(e.target.value)} className="pr-8 pl-8 [&::-webkit-search-cancel-button]:hidden" />
      {text && (
        <button
          type="button"
          aria-label="Clear search"
          className="absolute top-2.5 right-2.5 text-muted-foreground hover:text-foreground"
          onClick={() => {
            setText("")
            const next = new URLSearchParams(search)
            next.delete("search")
            setSearch(next)
          }}
        >
          <X className="size-4" />
        </button>
      )}
    </form>
  )
}

/** A column header that cycles the sort: ascending, descending, then none. */
function SortHeader({ resource, field, search, setSearch }: {
  resource: AdminResource
  field: string
  search: URLSearchParams
  setSearch: (next: URLSearchParams) => void
}) {
  const label = field === ID ? "ID" : humanize(field)
  if (!resource.sortable.includes(field)) return <>{label}</>
  const direction = sortOf(search, field)
  const cycle = () => {
    const next = new URLSearchParams(search)
    if (direction === null) next.set("sort", field)
    else if (direction === "asc") next.set("sort", `-${field}`)
    else next.delete("sort")
    setSearch(next)
  }
  const Icon = direction === "asc" ? ArrowUp : direction === "desc" ? ArrowDown : ArrowUpDown
  return (
    <button
      type="button"
      onClick={cycle}
      className="inline-flex items-center gap-1 hover:text-foreground"
    >
      {label}
      <Icon className={direction ? "size-3.5" : "size-3.5 opacity-40"} />
    </button>
  )
}

function sortOf(search: URLSearchParams, field: string): "asc" | "desc" | null {
  const sort = search.get("sort")
  return sort === field ? "asc" : sort === `-${field}` ? "desc" : null
}

function ariaSort(search: URLSearchParams, field: string): "ascending" | "descending" | undefined {
  const direction = sortOf(search, field)
  return direction === "asc" ? "ascending" : direction === "desc" ? "descending" : undefined
}
