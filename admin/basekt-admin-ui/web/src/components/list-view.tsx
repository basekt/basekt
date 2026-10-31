import { useEffect, useState, type DragEvent, type FormEvent } from "react"
import {
  ArrowDown,
  ArrowUp,
  ArrowUpDown,
  ChevronLeft,
  ChevronRight,
  ExternalLink,
  GripVertical,
  LayoutGrid,
  Plus,
  Search,
  Table2,
  X,
} from "lucide-react"
import { ActionButtons } from "@/components/action-button"
import { BulkActions, BulkFailures, ExportButton } from "@/components/bulk-actions"
import { ErrorState } from "@/components/error-state"
import { FilterBar } from "@/components/filter-bar"
import { RecordInspector } from "@/components/record-view"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Dialog, DialogContent, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Skeleton } from "@/components/ui/skeleton"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { FilePreview } from "@/forms/file-widget"
import { formatValue, humanize, ID, recordId } from "@/lib/fields"
import { isSafeHref, recordLinks } from "@/lib/links"
import { useAsync } from "@/lib/use-async"
import { cn } from "@/lib/utils"
import { can, usePanel } from "@/panel"
import { Link, useRouter } from "@/router"
import type { AdminCardView, AdminField, AdminResource, BulkFailure, JsonObject } from "@wire"

/** One page's place in the list: the cursor that fetches it and the row number it starts at. */
interface PageMark {
  cursor: string | null
  start: number
}

const UI_SEARCH_PARAMS = new Set(["_view", "_inspect"])

function serverSearch(search: URLSearchParams): URLSearchParams {
  const next = new URLSearchParams(search)
  for (const key of UI_SEARCH_PARAMS) next.delete(key)
  return next
}

function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => (typeof window !== "undefined" && window.matchMedia ? window.matchMedia(query).matches : false))
  useEffect(() => {
    if (typeof window === "undefined" || !window.matchMedia) return
    const list = window.matchMedia(query)
    const onChange = (event: MediaQueryListEvent) => setMatches(event.matches)
    setMatches(list.matches)
    list.addEventListener("change", onChange)
    return () => list.removeEventListener("change", onChange)
  }, [query])
  return matches
}

/**
 * A resource's records: columns or cards from the schema, search, filters and sort kept in the URL, optional drag/button
 * reordering, optional split or sheet record inspection, and cursor pagination with the total.
 */
export function ListView({ resource, search }: { resource: AdminResource; search: URLSearchParams }) {
  // A new search, sort or filter starts again from the first page; view-mode and inspector selection keep the current page.
  return <ListPages key={`${resource.name}?${serverSearch(search).toString()}`} resource={resource} search={search} />
}

function ListPages({ resource, search }: { resource: AdminResource; search: URLSearchParams }) {
  const { api } = usePanel()
  const { navigate } = useRouter()
  const [pages, setPages] = useState<PageMark[]>([{ cursor: null, start: 0 }])
  const mark = pages[pages.length - 1]
  const querySearch = serverSearch(search)
  const queryKey = querySearch.toString()
  const params = Object.fromEntries(querySearch)
  const page = useAsync(
    () => api.list(resource.name, mark.cursor ? { ...params, cursor: mark.cursor } : params),
    [api, resource.name, queryKey, mark.cursor],
  )

  const setSearch = (next: URLSearchParams) => navigate({ screen: "list", resource: resource.name, search: next }, { replace: true })
  const cards = resource.view?.cards ?? null
  const detailLayout = resource.view?.detailLayout ?? "page"
  const orderField = resource.view?.orderField ?? null
  const viewMode: "grid" | "table" = cards && search.get("_view") !== "table" ? "grid" : "table"
  const inspectedId = detailLayout !== "page" ? search.get("_inspect") : null
  const compactSplit = useMediaQuery("(max-width: 1023px)")

  const setViewMode = (mode: "grid" | "table") => {
    const next = new URLSearchParams(search)
    if (mode === "table") next.set("_view", "table")
    else next.delete("_view")
    setSearch(next)
  }

  const inspectRecord = (id: string | null) => {
    const next = new URLSearchParams(search)
    if (id) next.set("_inspect", id)
    else next.delete("_inspect")
    setSearch(next)
  }

  const fields: (AdminField | { name: string; type: "id" })[] = [{ name: ID, type: "id" }, ...resource.fields]
  const items = page.data?.items ?? []
  // Selection is per page: moving to another page or reloading starts with none.
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [bulkFailed, setBulkFailed] = useState<BulkFailure[]>([])
  const [reordering, setReordering] = useState(false)
  const [reorderError, setReorderError] = useState<unknown>(null)
  const [dragIndex, setDragIndex] = useState<number | null>(null)

  const selectable = can(resource, "delete")
  const reorderable = orderField !== null && can(resource, "update")
  const pageIds = items.map(recordId)
  const allSelected = pageIds.length > 0 && pageIds.every((id) => selected.has(id))
  const toggle = (id: string, on: boolean) =>
    setSelected((current) => {
      const next = new Set(current)
      if (on) next.add(id)
      else next.delete(id)
      return next
    })
  const linked = resource.links.length > 0
  const columns = fields.length + (selectable ? 1 : 0) + (reorderable ? 1 : 0) + (linked ? 1 : 0)
  const afterBulkDelete = (failed: BulkFailure[]) => {
    setBulkFailed(failed)
    setSelected(new Set(failed.map((f) => f.id)))
    page.reload()
  }

  const moveRecord = async (fromIndex: number, toIndex: number) => {
    if (!orderField || fromIndex === toIndex || fromIndex < 0 || toIndex < 0 || fromIndex >= items.length || toIndex >= items.length) {
      return
    }
    const existingPositions = items.map((item) => item[orderField])
    const allDistinctNumbers =
      existingPositions.every((value): value is number => typeof value === "number" && Number.isFinite(value)) &&
      new Set(existingPositions).size === existingPositions.length
    const slotValues = allDistinctNumbers
      ? (existingPositions as number[])
      : items.map((_, index) => mark.start + index + 1)

    const reordered = [...items]
    const [moved] = reordered.splice(fromIndex, 1)
    reordered.splice(toIndex, 0, moved)

    setReordering(true)
    setReorderError(null)
    try {
      for (let index = 0; index < reordered.length; index++) {
        const record = reordered[index]
        const nextValue = slotValues[index]
        if (record[orderField] !== nextValue) {
          await api.update(resource.name, recordId(record), { [orderField]: nextValue })
        }
      }
      page.reload()
    } catch (error) {
      setReorderError(error)
    } finally {
      setReordering(false)
      setDragIndex(null)
    }
  }

  const showSplitPane = detailLayout === "split" && inspectedId !== null && !compactSplit
  const showSheetDrawer = inspectedId !== null && (detailLayout === "sheet" || (detailLayout === "split" && compactSplit))

  const listBody = (
    <div className="grid min-w-0 gap-4">
      {page.error ? (
        <ErrorState error={page.error} onRetry={page.reload} />
      ) : viewMode === "grid" && cards ? (
        <CardGrid
          resource={resource}
          cards={cards}
          items={items}
          loading={page.loading && !page.data}
          hasQuery={querySearch.size > 0}
          selectable={selectable}
          selected={selected}
          onToggle={toggle}
          detailLayout={detailLayout}
          inspectedId={inspectedId}
          onInspect={inspectRecord}
          reorderable={reorderable}
          reordering={reordering}
          dragIndex={dragIndex}
          onDragIndex={setDragIndex}
          onMove={moveRecord}
        />
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
                {reorderable && (
                  <TableHead className="w-24 text-right">
                    <span className="sr-only">Order</span>
                  </TableHead>
                )}
                {linked && (
                  <TableHead className="w-10">
                    <span className="sr-only">Links</span>
                  </TableHead>
                )}
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
                    {reorderable && <TableCell />}
                    {linked && <TableCell />}
                  </TableRow>
                ))
              ) : items.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={columns} className="h-24 text-center text-muted-foreground">
                    No records{querySearch.size > 0 ? " match" : " yet"}.
                  </TableCell>
                </TableRow>
              ) : (
                items.map((record, index) => (
                  <RecordRow
                    key={recordId(record)}
                    resource={resource}
                    record={record}
                    index={index}
                    totalCount={items.length}
                    selected={selectable ? selected.has(recordId(record)) : undefined}
                    onSelect={(on) => toggle(recordId(record), on)}
                    detailLayout={detailLayout}
                    inspected={inspectedId === recordId(record)}
                    onInspect={() => inspectRecord(recordId(record))}
                    reorderable={reorderable}
                    reordering={reordering}
                    dragIndex={dragIndex}
                    onDragIndex={setDragIndex}
                    onMove={moveRecord}
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

  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <h1 className="text-2xl font-semibold tracking-tight">{humanize(resource.name)}</h1>
          {resource.readOnly && (
            <Badge variant="secondary" title="Its records come from the application and can't be edited here.">Read-only</Badge>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {cards && (
            <div role="group" aria-label="View mode" className="inline-flex rounded-md border p-0.5">
              <Button
                type="button"
                variant={viewMode === "grid" ? "secondary" : "ghost"}
                size="sm"
                aria-pressed={viewMode === "grid"}
                onClick={() => setViewMode("grid")}
              >
                <LayoutGrid /> Grid
              </Button>
              <Button
                type="button"
                variant={viewMode === "table" ? "secondary" : "ghost"}
                size="sm"
                aria-pressed={viewMode === "table"}
                onClick={() => setViewMode("table")}
              >
                <Table2 /> Table
              </Button>
            </div>
          )}
          <ActionButtons resource={resource} target="resource" onDone={() => page.reload()} />
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
      {reorderError != null && <ErrorState error={reorderError} onRetry={() => setReorderError(null)} />}

      <div className={cn("grid items-start gap-6", showSplitPane && "lg:grid-cols-[minmax(0,1fr)_24rem]")}>
        {listBody}
        {showSplitPane && (
          <aside aria-label="Record inspector" className="rounded-lg border bg-card p-4 lg:sticky lg:top-6">
            <RecordInspector
              key={`${resource.name}/${inspectedId}`}
              resource={resource}
              id={inspectedId}
              onClose={() => inspectRecord(null)}
              onChanged={() => page.reload()}
              onDeleted={() => page.reload()}
            />
          </aside>
        )}
      </div>

      {showSheetDrawer && inspectedId && (
        <Dialog open onOpenChange={(open) => { if (!open) inspectRecord(null) }}>
          <DialogContent
            showCloseButton={false}
            aria-describedby={undefined}
            className="top-0 right-0 left-auto h-full max-w-xl translate-x-0 translate-y-0 content-start overflow-y-auto rounded-none border-l p-6 sm:max-w-xl"
          >
            <DialogTitle className="sr-only">Inspect {inspectedId}</DialogTitle>
            <RecordInspector
              key={`${resource.name}/${inspectedId}`}
              resource={resource}
              id={inspectedId}
              onClose={() => inspectRecord(null)}
              onChanged={() => page.reload()}
              onDeleted={() => page.reload()}
            />
          </DialogContent>
        </Dialog>
      )}
    </div>
  )
}

function CardGrid({
  resource,
  cards,
  items,
  loading,
  hasQuery,
  selectable,
  selected,
  onToggle,
  detailLayout,
  inspectedId,
  onInspect,
  reorderable,
  reordering,
  dragIndex,
  onDragIndex,
  onMove,
}: {
  resource: AdminResource
  cards: AdminCardView
  items: JsonObject[]
  loading: boolean
  hasQuery: boolean
  selectable: boolean
  selected: Set<string>
  onToggle: (id: string, on: boolean) => void
  detailLayout: "page" | "split" | "sheet"
  inspectedId: string | null
  onInspect: (id: string) => void
  reorderable: boolean
  reordering: boolean
  dragIndex: number | null
  onDragIndex: (index: number | null) => void
  onMove: (from: number, to: number) => void
}) {
  if (loading) {
    return (
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
        {[0, 1, 2].map((index) => (
          <div key={index} className="flex items-center gap-3 rounded-lg border p-3">
            <Skeleton className="size-12 rounded-md" />
            <div className="grid flex-1 gap-2">
              <Skeleton className="h-4 w-32" />
              <Skeleton className="h-3 w-20" />
            </div>
          </div>
        ))}
      </div>
    )
  }
  if (items.length === 0) {
    return (
      <div className="flex h-24 items-center justify-center rounded-md border text-sm text-muted-foreground">
        No records{hasQuery ? " match" : " yet"}.
      </div>
    )
  }

  const fieldByName = new Map(resource.fields.map((field) => [field.name, field]))
  const mediaField = cards.media ? fieldByName.get(cards.media) : undefined
  const titleField = cards.title ? fieldByName.get(cards.title) : undefined
  const subtitleField = cards.subtitle ? fieldByName.get(cards.subtitle) : undefined
  const badgeField = cards.badge ? fieldByName.get(cards.badge) : undefined

  return (
    <div role="list" aria-label={`${humanize(resource.name)} cards`} className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
      {items.map((record, index) => {
        const id = recordId(record)
        const active = inspectedId === id
        const rawTitle = cards.title ? record[cards.title] : null
        const title = rawTitle !== null && rawTitle !== undefined && rawTitle !== "" ? formatValue(titleField, rawTitle) : id
        const rawSubtitle = cards.subtitle ? record[cards.subtitle] : null
        const subtitle = rawSubtitle !== null && rawSubtitle !== undefined && rawSubtitle !== "" ? formatValue(subtitleField, rawSubtitle) : null
        const rawBadge = cards.badge ? record[cards.badge] : null
        const badge = rawBadge !== null && rawBadge !== undefined && rawBadge !== "" ? formatValue(badgeField, rawBadge) : null
        const rawMedia = cards.media ? record[cards.media] : null
        const mediaValue = typeof rawMedia === "string" && rawMedia !== "" ? rawMedia : null

        const dragProps = reorderable
          ? {
              draggable: !reordering,
              onDragStart: (event: DragEvent) => {
                event.dataTransfer.effectAllowed = "move"
                onDragIndex(index)
              },
              onDragOver: (event: DragEvent) => {
                event.preventDefault()
                event.dataTransfer.dropEffect = "move"
              },
              onDrop: (event: DragEvent) => {
                event.preventDefault()
                if (dragIndex !== null && dragIndex !== index) onMove(dragIndex, index)
              },
              onDragEnd: () => onDragIndex(null),
            }
          : {}

        return (
          <div
            key={id}
            role="listitem"
            data-selected={active || undefined}
            className={cn(
              "flex items-start gap-3 rounded-lg border bg-card p-3 transition-colors hover:bg-accent/30",
              active && "border-primary/60 ring-2 ring-primary/30",
              dragIndex === index && "opacity-50",
            )}
            {...dragProps}
          >
            {selectable && (
              <Checkbox
                aria-label={`Select ${id}`}
                checked={selected.has(id)}
                onCheckedChange={(on) => onToggle(id, on === true)}
                className="mt-1"
              />
            )}
            {mediaValue && (
              mediaField?.type === "file" ? (
                <div className="shrink-0">
                  <FilePreview fileKey={mediaValue} />
                </div>
              ) : isSafeHref(mediaValue) ? (
                <img
                  src={mediaValue}
                  alt=""
                  loading="lazy"
                  className="size-12 shrink-0 rounded-md border bg-muted/40 object-contain p-1"
                />
              ) : null
            )}
            <div className="min-w-0 flex-1">
              <div className="flex items-start justify-between gap-2">
                {detailLayout === "page" ? (
                  <Link
                    to={{ screen: "detail", resource: resource.name, id }}
                    className="truncate font-medium underline decoration-muted-foreground/40 underline-offset-4 hover:decoration-foreground"
                  >
                    {title}
                  </Link>
                ) : (
                  <button
                    type="button"
                    onClick={() => onInspect(id)}
                    aria-pressed={active}
                    className="truncate text-left font-medium underline decoration-muted-foreground/40 underline-offset-4 hover:decoration-foreground"
                  >
                    {title}
                  </button>
                )}
                {badge && <Badge variant="outline" className="shrink-0">{badge}</Badge>}
              </div>
              <div className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs text-muted-foreground">
                {title !== id && <span className="font-mono">{id}</span>}
                {subtitle && <span className="truncate">{subtitle}</span>}
              </div>
            </div>
            {(reorderable || resource.links.length > 0) && (
              <div className="flex shrink-0 items-center gap-1">
                {reorderable && (
                  <ReorderControls
                    id={id}
                    index={index}
                    totalCount={items.length}
                    disabled={reordering}
                    onMove={onMove}
                  />
                )}
                {recordLinks(resource.links, record).map((link) => (
                  <a
                    key={link.label}
                    href={link.href}
                    target="_blank"
                    rel="noopener noreferrer"
                    aria-label={`${link.label}: ${id}`}
                    title={link.label}
                    className="text-muted-foreground hover:text-foreground"
                  >
                    <ExternalLink className="size-4" />
                  </a>
                ))}
              </div>
            )}
          </div>
        )
      })}
    </div>
  )
}

/** A record's row; with [selected] set, it starts with a checkbox for bulk actions. */
function RecordRow({
  resource,
  record,
  index,
  totalCount,
  selected,
  onSelect,
  detailLayout,
  inspected,
  onInspect,
  reorderable,
  reordering,
  dragIndex,
  onDragIndex,
  onMove,
}: {
  resource: AdminResource
  record: JsonObject
  index: number
  totalCount: number
  selected?: boolean
  onSelect: (on: boolean) => void
  detailLayout: "page" | "split" | "sheet"
  inspected: boolean
  onInspect: () => void
  reorderable: boolean
  reordering: boolean
  dragIndex: number | null
  onDragIndex: (index: number | null) => void
  onMove: (from: number, to: number) => void
}) {
  const id = recordId(record)
  const dragProps = reorderable
    ? {
        draggable: !reordering,
        onDragStart: (event: DragEvent) => {
          event.dataTransfer.effectAllowed = "move"
          onDragIndex(index)
        },
        onDragOver: (event: DragEvent) => {
          event.preventDefault()
          event.dataTransfer.dropEffect = "move"
        },
        onDrop: (event: DragEvent) => {
          event.preventDefault()
          if (dragIndex !== null && dragIndex !== index) onMove(dragIndex, index)
        },
        onDragEnd: () => onDragIndex(null),
      }
    : {}

  return (
    <TableRow
      data-state={selected ? "selected" : undefined}
      data-inspected={inspected || undefined}
      className={cn(inspected && "bg-accent/40", dragIndex === index && "opacity-50")}
      {...dragProps}
    >
      {selected !== undefined && (
        <TableCell>
          <Checkbox aria-label={`Select ${id}`} checked={selected} onCheckedChange={(on) => onSelect(on === true)} />
        </TableCell>
      )}
      <TableCell className="font-mono text-xs">
        {detailLayout === "page" ? (
          <Link to={{ screen: "detail", resource: resource.name, id }} className="underline decoration-muted-foreground/40 underline-offset-4 hover:decoration-foreground">
            {id}
          </Link>
        ) : (
          <button
            type="button"
            onClick={onInspect}
            aria-pressed={inspected}
            className="underline decoration-muted-foreground/40 underline-offset-4 hover:decoration-foreground"
          >
            {id}
          </button>
        )}
      </TableCell>
      {resource.fields.map((field) => (
        <TableCell key={field.name} className="max-w-64 truncate">
          <Cell field={field} record={record} />
        </TableCell>
      ))}
      {reorderable && (
        <TableCell className="text-right">
          <ReorderControls id={id} index={index} totalCount={totalCount} disabled={reordering} onMove={onMove} />
        </TableCell>
      )}
      {resource.links.length > 0 && (
        <TableCell className="text-right">
          <span className="inline-flex gap-1">
            {recordLinks(resource.links, record).map((link) => (
              <a
                key={link.label}
                href={link.href}
                target="_blank"
                rel="noopener noreferrer"
                aria-label={`${link.label}: ${id}`}
                title={link.label}
                className="text-muted-foreground hover:text-foreground"
              >
                <ExternalLink className="size-4" />
              </a>
            ))}
          </span>
        </TableCell>
      )}
    </TableRow>
  )
}

function ReorderControls({
  id,
  index,
  totalCount,
  disabled,
  onMove,
}: {
  id: string
  index: number
  totalCount: number
  disabled: boolean
  onMove: (from: number, to: number) => void
}) {
  return (
    <span className="inline-flex items-center gap-0.5">
      <GripVertical aria-hidden="true" className="size-3.5 cursor-grab text-muted-foreground/60" />
      <Button
        type="button"
        variant="ghost"
        size="icon-xs"
        aria-label={`Move ${id} up`}
        disabled={disabled || index === 0}
        onClick={() => onMove(index, index - 1)}
      >
        <ArrowUp />
      </Button>
      <Button
        type="button"
        variant="ghost"
        size="icon-xs"
        aria-label={`Move ${id} down`}
        disabled={disabled || index === totalCount - 1}
        onClick={() => onMove(index, index + 1)}
      >
        <ArrowDown />
      </Button>
    </span>
  )
}

function Cell({ field, record }: { field: AdminField; record: JsonObject }) {
  const value = record[field.name]
  if (value === null || value === undefined) return <span className="text-muted-foreground">—</span>
  if (field.type === "enum") return <Badge variant="outline">{String(value)}</Badge>
  if (field.type === "file" && typeof value === "string") return <FilePreview fileKey={value} />
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
