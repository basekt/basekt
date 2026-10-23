import { useState } from "react"
import { Download, Trash2, X } from "lucide-react"
import { toast } from "sonner"
import type { AdminResource, BulkFailure } from "@wire"
import type { ListParams } from "@/api/client"
import { ErrorState } from "@/components/error-state"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
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
import { Button } from "@/components/ui/button"
import { humanize } from "@/lib/fields"
import { usePanel } from "@/panel"

/**
 * The bar over a list with records selected: how many, and a confirmed bulk delete. [onDone] gets the records the
 * API couldn't delete, each with its reason.
 */
export function BulkActions({ resource, ids, onClear, onDone }: {
  resource: AdminResource
  ids: string[]
  onClear: () => void
  onDone: (failed: BulkFailure[]) => void
}) {
  const { api } = usePanel()
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<unknown>(null)

  const remove = async () => {
    setBusy(true)
    setFailure(null)
    try {
      const result = await api.bulkDelete(resource.name, ids)
      if (result.deleted.length > 0) toast.success(`Deleted ${result.deleted.length} of ${ids.length}.`)
      onDone(result.failed)
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="grid gap-2">
      <div role="toolbar" aria-label="Selected records" className="flex flex-wrap items-center gap-2 rounded-md border bg-muted/40 px-3 py-2 text-sm">
        <span className="font-medium">{ids.length} selected</span>
        <AlertDialog>
          <AlertDialogTrigger asChild>
            <Button variant="destructive" size="sm" disabled={busy}>
              <Trash2 /> Delete
            </Button>
          </AlertDialogTrigger>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>Delete {ids.length} {ids.length === 1 ? "record" : "records"}?</AlertDialogTitle>
              <AlertDialogDescription>
                This removes them from {humanize(resource.name).toLowerCase()}. It can't be undone.
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>Cancel</AlertDialogCancel>
              <AlertDialogAction onClick={remove} className="bg-destructive text-white hover:bg-destructive/90">Delete</AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
        <Button variant="ghost" size="sm" onClick={onClear}>
          <X /> Clear
        </Button>
      </div>
      {failure != null && <ErrorState error={failure} />}
    </div>
  )
}

/** The records a bulk delete left, each with the API's reason. */
export function BulkFailures({ failed, onDismiss }: { failed: BulkFailure[]; onDismiss: () => void }) {
  return (
    <Alert variant="destructive">
      <AlertTitle>{failed.length} not deleted</AlertTitle>
      <AlertDescription>
        <ul className="list-disc pl-4">
          {failed.map((f) => (
            <li key={f.id}>
              <span className="font-mono">{f.id}</span>: {f.error.message}
            </li>
          ))}
        </ul>
        <Button size="sm" variant="outline" className="mt-2" onClick={onDismiss}>
          Dismiss
        </Button>
      </AlertDescription>
    </Alert>
  )
}

/** Downloads the list as CSV, with its current search, sort and filters, sending the panel's credentials. */
export function ExportButton({ resource, params }: { resource: AdminResource; params: ListParams }) {
  const { api } = usePanel()
  const [busy, setBusy] = useState(false)
  const download = async () => {
    setBusy(true)
    try {
      const blob = await api.exportCsv(resource.name, params)
      const url = URL.createObjectURL(blob)
      const link = Object.assign(document.createElement("a"), { href: url, download: `${resource.name}.csv` })
      document.body.append(link)
      link.click()
      link.remove()
      setTimeout(() => URL.revokeObjectURL(url), 0)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "The export failed.")
    } finally {
      setBusy(false)
    }
  }
  return (
    <Button variant="outline" onClick={download} disabled={busy}>
      <Download /> Export CSV
    </Button>
  )
}
