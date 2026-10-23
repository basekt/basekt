import type { AdminAggregatePage, AdminPage, AdminSchema, ApiError, BulkDeleteResult, JsonObject } from "@wire"

/** The call a failure came from: its method and path (with the query, never a token), and its request id, if any. */
export interface FailedCall {
  method: string
  path: string
  requestId?: string | null
}

/**
 * A failed call: the HTTP status and, when the server sent one, its typed error body. A status of 0 means no answer
 * came at all, as when the server is down or the network drops, so the server has nothing logged for it either.
 */
export class ApiFailure extends Error {
  readonly status: number
  readonly error: ApiError | null
  /** The method and path of the call, when known. */
  readonly call: FailedCall | null
  /** The id the server gave the call, which its logs name: from the error body, or the `X-Request-Id` header. */
  readonly requestId: string | null
  /** When it failed, as an ISO-8601 instant. */
  readonly at: string

  constructor(status: number, error: ApiError | null, call: FailedCall | null = null) {
    super(error?.message ?? (status === 0 ? "Couldn't reach the server." : `The admin API answered ${status}.`))
    this.status = status
    this.error = error
    this.call = call
    this.requestId = error?.requestId ?? call?.requestId ?? null
    this.at = new Date().toISOString()
  }

  /** No answer came: the server is down or unreachable, or the network failed. */
  get network(): boolean {
    return this.status === 0
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

/** One failed call, as the panel keeps it for a report: what was asked, what came back, and when. */
export interface FailureRecord {
  at: string
  method: string | null
  path: string | null
  status: number
  error: string | null
  message: string
  requestId: string | null
}

/**
 * The last failed calls of this page, newest first, for the viewer to copy or download when they report a problem.
 * They live in memory only, so nothing is left behind in the browser.
 */
export class FailureLog {
  private readonly records: FailureRecord[] = []

  constructor(private readonly size = 20) {}

  /** Keeps [failure], dropping the oldest beyond the log's size. */
  record(failure: ApiFailure): void {
    this.records.unshift(recordOf(failure))
    this.records.length = Math.min(this.records.length, this.size)
  }

  /** The kept failures, newest first. */
  entries(): FailureRecord[] {
    return [...this.records]
  }
}

/** [failure] as a record of the log. */
export function recordOf(failure: ApiFailure): FailureRecord {
  return {
    at: failure.at,
    method: failure.call?.method ?? null,
    path: failure.call?.path ?? null,
    status: failure.status,
    error: failure.error?.error ?? null,
    message: failure.message,
    requestId: failure.requestId,
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
    /** Where failed calls are kept for a report. */
    readonly failures: FailureLog = new FailureLog(),
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

  /** Runs [action] on the record [id], or on the whole resource without one. Resolves to its answer, or null. */
  async runAction(resource: string, action: string, id: string | undefined, input: JsonObject): Promise<JsonObject | null> {
    const base = id === undefined ? `/resources/${encodeURIComponent(resource)}` : `${this.records(resource)}/${encodeURIComponent(id)}`
    const response = await this.send("POST", `${base}/actions/${encodeURIComponent(action)}`, input)
    return response.status === 204 ? null : ((await response.json()) as JsonObject)
  }

  /** Grouped totals of [resource]'s records: `groupBy`, a measure such as `sum=total`, `sort` and `limit`, plus filters. */
  aggregate(resource: string, params: ListParams): Promise<AdminAggregatePage> {
    return this.json("GET", `/resources/${encodeURIComponent(resource)}/aggregate${query(params)}`)
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
    const call = { method, path: this.apiPath + path }
    let response: Response
    try {
      response = await this.fetchImpl(call.path, {
        method,
        headers,
        credentials: "same-origin",
        body: body === undefined ? undefined : JSON.stringify(body),
      })
    } catch {
      throw this.failed(new ApiFailure(0, null, call))
    }
    if (!response.ok) {
      throw this.failed(new ApiFailure(response.status, await errorBody(response), { ...call, requestId: response.headers.get("X-Request-Id") }))
    }
    return response
  }

  private failed(failure: ApiFailure): ApiFailure {
    this.failures.record(failure)
    return failure
  }
}

async function errorBody(response: Response): Promise<ApiError | null> {
  try {
    const body = (await response.json()) as Partial<ApiError>
    return typeof body.error === "string" && typeof body.message === "string"
      ? { error: body.error, message: body.message, fields: body.fields ?? [], requestId: body.requestId ?? null }
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
