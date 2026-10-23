import type { ApiError } from "@wire"
import { ApiFailure } from "@/api/client"

/** A stored file, as BaseKtFiles answers an upload. Mirrors `FileRef` in `basekt-files`. */
export interface FileRef {
  key: string
  bucket: string
  name: string
  size: number
  contentType: string
  url: string
}

/** The image types a browser previews. Other files are shown by name. */
const imageName = /\.(png|jpe?g|gif|webp|avif|bmp)$/i

/** Whether the file at [key] is one the panel previews as an image, judging by its name. */
export function isImageKey(key: string): boolean {
  return imageName.test(key)
}

/** The file name a key ends with. */
export function fileName(key: string): string {
  return key.slice(key.lastIndexOf("/") + 1)
}

/**
 * Uploads and reads files through BaseKtFiles at [filesPath], with the same credentials the admin API gets: the
 * browser's cookies, and the bearer token when there is one.
 */
export class FilesApi {
  constructor(
    readonly filesPath: string,
    private readonly token: () => string | null,
    private readonly fetchImpl: typeof fetch = (...args) => fetch(...args),
    private readonly xhr: () => XMLHttpRequest = () => new XMLHttpRequest(),
  ) {}

  /** The URL of the file at [key]. */
  url(key: string): string {
    return `${this.filesPath}/${key.split("/").map(encodeURIComponent).join("/")}`
  }

  /** Uploads [file] to [bucket], reporting progress from 0 to 1. Fails with an [ApiFailure], such as a 413 or 415. */
  upload(bucket: string, file: Blob & { name?: string }, onProgress?: (fraction: number) => void): Promise<FileRef> {
    return new Promise((resolve, reject) => {
      const request = this.xhr()
      request.open("POST", `${this.filesPath}/${encodeURIComponent(bucket)}`)
      request.withCredentials = true
      request.setRequestHeader("Accept", "application/json")
      const token = this.token()
      if (token) request.setRequestHeader("Authorization", `Bearer ${token}`)
      request.upload?.addEventListener("progress", (event) => {
        if (event.lengthComputable && onProgress) onProgress(event.loaded / event.total)
      })
      request.addEventListener("load", () => {
        const body = parse(request.responseText)
        if (request.status >= 200 && request.status < 300) resolve(body as FileRef)
        else reject(new ApiFailure(request.status, asApiError(body)))
      })
      request.addEventListener("error", () => reject(new ApiFailure(0, null)))
      const form = new FormData()
      form.append("file", file, file.name ?? "upload")
      request.send(form)
    })
  }

  /**
   * Fetches the file at [key] with the panel's credentials and returns a `blob:` URL for it, so a preview works even
   * when the file needs a bearer token that an `<img>` can't send. Revoke it with `URL.revokeObjectURL`.
   */
  async objectUrl(key: string): Promise<string> {
    const headers: Record<string, string> = {}
    const token = this.token()
    if (token) headers.Authorization = `Bearer ${token}`
    const response = await this.fetchImpl(this.url(key), { headers, credentials: "same-origin" })
    if (!response.ok) throw new ApiFailure(response.status, null)
    return URL.createObjectURL(await response.blob())
  }
}

function parse(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

function asApiError(body: unknown): ApiError | null {
  const error = body as Partial<ApiError> | null
  return error && typeof error.error === "string" && typeof error.message === "string"
    ? { error: error.error, message: error.message, fields: error.fields ?? [] }
    : null
}
