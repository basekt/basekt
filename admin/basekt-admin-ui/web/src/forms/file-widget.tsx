import { useEffect, useRef, useState } from "react"
import { FileText, Upload, X } from "lucide-react"
import { ApiFailure } from "@/api/client"
import { fileName, isImageKey } from "@/api/files"
import { Button } from "@/components/ui/button"
import type { WidgetProps } from "@/forms/widgets"
import { cn } from "@/lib/utils"
import { usePanel } from "@/panel"

/**
 * A file field: a preview of the current file, an upload button that sends a new one to the field's bucket with a
 * progress bar, and a button to clear it. The field's value is the uploaded file's key.
 */
export function FileWidget({ field, id, value, onChange, invalid, describedBy }: WidgetProps) {
  const { files } = usePanel()
  const input = useRef<HTMLInputElement>(null)
  const [progress, setProgress] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const key = typeof value === "string" && value !== "" ? value : null

  const upload = async (file: File) => {
    if (!field.bucket) return
    setError(null)
    setProgress(0)
    try {
      const ref = await files.upload(field.bucket, file, setProgress)
      onChange(ref.key)
    } catch (failure) {
      setError(failure instanceof ApiFailure && failure.error ? failure.error.message : "The upload failed.")
    } finally {
      setProgress(null)
      if (input.current) input.current.value = ""
    }
  }

  return (
    <div className="grid gap-2">
      <div className="flex flex-wrap items-center gap-3">
        {key && <FilePreview fileKey={key} size="lg" />}
        <input
          ref={input}
          id={id}
          type="file"
          className="sr-only"
          aria-invalid={invalid}
          aria-describedby={describedBy}
          disabled={progress !== null || !field.bucket}
          onChange={(e) => {
            const file = e.target.files?.[0]
            if (file) void upload(file)
          }}
        />
        <Button type="button" variant="outline" size="sm" disabled={progress !== null || !field.bucket} onClick={() => input.current?.click()}>
          <Upload /> {key ? "Replace" : "Upload"}
        </Button>
        {key && !field.required && (
          <Button type="button" variant="ghost" size="sm" onClick={() => onChange(null)}>
            <X /> Remove
          </Button>
        )}
      </div>
      {progress !== null && (
        <div role="progressbar" aria-label="Uploading" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(progress * 100)} className="h-1.5 w-full max-w-72 overflow-hidden rounded-full bg-muted">
          <div className="h-full bg-primary transition-[width]" style={{ width: `${Math.round(progress * 100)}%` }} />
        </div>
      )}
      {error && <p className="text-sm text-destructive">{error}</p>}
    </div>
  )
}

/** A file shown small: an image's thumbnail, or an icon and the file's name. Images load with the panel's credentials. */
export function FilePreview({ fileKey, size = "sm" }: { fileKey: string; size?: "sm" | "lg" }) {
  const { files } = usePanel()
  const [src, setSrc] = useState<string | null>(null)
  const image = isImageKey(fileKey)
  useEffect(() => {
    if (!image) return
    let url: string | null = null
    let current = true
    files.objectUrl(fileKey).then(
      (made) => {
        url = made
        if (current) setSrc(made)
        else URL.revokeObjectURL(made)
      },
      () => current && setSrc(null),
    )
    return () => {
      current = false
      if (url) URL.revokeObjectURL(url)
    }
  }, [files, fileKey, image])

  const box = size === "lg" ? "size-20" : "size-8"
  if (image && src) {
    return <img src={src} alt={fileName(fileKey)} className={cn(box, "rounded-md border object-cover")} />
  }
  return (
    <span className="inline-flex items-center gap-1.5 text-sm" title={fileKey}>
      <FileText className={cn(size === "lg" ? "size-5" : "size-4", "shrink-0 text-muted-foreground")} />
      <span className="max-w-48 truncate">{fileName(fileKey)}</span>
    </span>
  )
}
