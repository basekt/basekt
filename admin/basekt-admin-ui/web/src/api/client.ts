import type { AdminPage, AdminSchema, ApiError, BulkDeleteResult, JsonObject } from "@wire"

/** A failed call: the HTTP status and, when the server sent one, its typed error body. */
export class ApiFailure extends Error {
  readonly status: number
  readonly error: ApiError | null

  constructor(status: number, error: ApiError | null) {
    super(error?.message ?? `The admin API answered ${status}.`)
    this.status = status
    this.error = error
  }

  /** The caller has no credentials, or they were refused. */
  get unauthorized(): boolean {
    return this.status === 401
  }

  /** The caller's roles don't allow this. */
  get forbidden(): boolean {
    return this.status === 403
  }
}

/** The list parameters the admin API takes, already in its `field[op]=value` form. */
export type ListParams = Record<string, string>

/**
 * Calls the JSON admin API at [apiPath]. It sends the browser's cookies, so cookie sessions work, and the bearer
 * token [token] returns, if any.
 */
export class AdminApi {
  constructor(
    readonly apiPath: string,
    private readonly token: () => string | null,
    private readonly fetchImpl: typeof fetch = (...args) => fetch(...args),
  ) {}

  schema(): Promise<AdminSchema> {
    return this.json("GET", "/schema")
  }

  list(resource: string, params: ListParams): Promise<AdminPage> {
    return this.json("GET", `${this.records(resource)}${query(params)}`)
  }

  get(resource: string, id: string): Promise<JsonObject> {
    return this.json("GET", `${this.records(resource)}/${encodeURIComponent(id)}`)
  }

  create(resource: string, record: JsonObject): Promise<JsonObject> {
    return this.json("POST", this.records(resource), record)
  }

  update(resource: string, id: string, changes: JsonObject): Promise<JsonObject> {
    return this.json("PATCH", `${this.records(resource)}/${encodeURIComponent(id)}`, changes)
  }

  async remove(resource: string, id: string): Promise<void> {
    await this.send("DELETE", `${this.records(resource)}/${encodeURIComponent(id)}`)
  }

  bulkDelete(resource: string, ids: string[]): Promise<BulkDeleteResult> {
    return this.json("POST", `/resources/${encodeURIComponent(resource)}/bulk-delete`, { ids })
  }

  /** Downloads the CSV export with the list's filters, sort and search, sending the same credentials. */
  async exportCsv(resource: string, params: ListParams): Promise<Blob> {
    const response = await this.send("GET", `/resources/${encodeURIComponent(resource)}/export.csv${query(params)}`)
    return response.blob()
  }

  private records(resource: string): string {
    return `/resources/${encodeURIComponent(resource)}/records`
  }

  private async json<T>(method: string, path: string, body?: unknown): Promise<T> {
    const response = await this.send(method, path, body)
    return (await response.json()) as T
  }

  private async send(method: string, path: string, body?: unknown): Promise<Response> {
    const headers: Record<string, string> = { Accept: "application/json" }
    const token = this.token()
    if (token) headers.Authorization = `Bearer ${token}`
    if (body !== undefined) headers["Content-Type"] = "application/json"
    const response = await this.fetchImpl(this.apiPath + path, {
      method,
      headers,
      credentials: "same-origin",
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    if (!response.ok) throw new ApiFailure(response.status, await errorBody(response))
    return response
  }
}

async function errorBody(response: Response): Promise<ApiError | null> {
  try {
    const body = (await response.json()) as Partial<ApiError>
    return typeof body.error === "string" && typeof body.message === "string"
      ? { error: body.error, message: body.message, fields: body.fields ?? [] }
      : null
  } catch {
    return null
  }
}

function query(params: ListParams): string {
  const search = new URLSearchParams(Object.entries(params).filter(([, value]) => value !== ""))
  const text = search.toString()
  return text ? `?${text}` : ""
}
