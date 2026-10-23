import type { AdminPage, AdminResource, AdminSchema, ApiError, JsonObject } from "@wire"
import type { PanelConfig } from "@/config"

// Two made-up schemas that use the panel differently: a lending library with references and decimals, and a help
// desk with enums, instants and a read-only role.

export const books: AdminResource = {
  name: "books",
  fields: [
    { name: "title", type: "string", required: true, values: null, target: null, rules: [{ kind: "length", min: 1, max: 200 }], bucket: null, scopedValues: null },
    { name: "pages", type: "int", required: false, values: null, target: null, rules: [{ kind: "min", value: "1" }], bucket: null, scopedValues: null },
    { name: "fee", type: "decimal", required: true, values: null, target: null, rules: [], bucket: null, scopedValues: null },
    { name: "lendable", type: "bool", required: false, values: null, target: null, rules: [], bucket: null, scopedValues: null },
    { name: "authorId", type: "reference", required: false, values: null, target: "authors", rules: [], bucket: null, scopedValues: null },
  ],
  relations: [{ name: "author", kind: "belongsTo", target: "authors", field: "authorId" }],
  operations: ["read", "create", "update", "delete"],
  searchable: ["title"],
  sortable: ["id", "title", "pages", "fee", "lendable", "authorId"],
}

export const authors: AdminResource = {
  name: "authors",
  fields: [{ name: "name", type: "string", required: true, values: null, target: null, rules: [], bucket: null, scopedValues: null }],
  relations: [{ name: "books", kind: "hasMany", target: "books", field: "authorId" }],
  operations: ["read", "create", "update", "delete"],
  searchable: ["name"],
  sortable: ["id", "name"],
}

export const librarySchema: AdminSchema = { version: 1, caller: { id: "librarian", roles: ["staff"] }, resources: [books, authors] }

export const tickets: AdminResource = {
  name: "tickets",
  fields: [
    { name: "subject", type: "string", required: true, values: null, target: null, rules: [], bucket: null, scopedValues: null },
    { name: "state", type: "enum", required: true, values: ["open", "waiting", "closed"], target: null, rules: [], bucket: null, scopedValues: null },
    { name: "openedAt", type: "instant", required: false, values: null, target: null, rules: [], bucket: null, scopedValues: null },
    { name: "views", type: "long", required: false, values: null, target: null, rules: [], bucket: null, scopedValues: null },
    { name: "screenshot", type: "file", required: false, values: null, target: null, rules: [], bucket: "screens", scopedValues: null },
  ],
  relations: [],
  operations: ["read"],
  searchable: ["subject", "state"],
  sortable: ["id", "subject", "state", "openedAt", "views", "screenshot"],
}

export const helpDeskSchema: AdminSchema = { version: 1, caller: { id: "viewer", roles: ["observer"] }, resources: [tickets] }

export const config: PanelConfig = { path: "/admin", apiPath: "/admin/api", filesPath: "/files", brand: { name: "Test", logo: null }, defaultMode: "light" }

export function page(items: JsonObject[], total: number | null = items.length, nextCursor: string | null = null): AdminPage {
  return { items, nextCursor, hasNext: nextCursor != null, total }
}

export function apiError(error: ApiError["error"], message: string, fields: ApiError["fields"] = []): ApiError {
  return { error, message, fields }
}

/** A recorded request. */
export interface Call {
  method: string
  url: URL
  headers: Record<string, string>
  body: unknown
}

type Handler = (call: Call) => { status?: number; body?: unknown } | undefined

/**
 * A fetch that answers from [handler] and records each call. Unhandled calls answer 404 with an ApiError, so a test
 * sees a missing route as a failure.
 */
export function fakeFetch(handler: Handler): typeof fetch & { calls: Call[] } {
  const calls: Call[] = []
  const impl = async (input: RequestInfo | URL, init?: RequestInit) => {
    const call: Call = {
      method: init?.method ?? "GET",
      url: new URL(String(input), "http://panel.test"),
      headers: (init?.headers ?? {}) as Record<string, string>,
      body: typeof init?.body === "string" ? JSON.parse(init.body) : undefined,
    }
    calls.push(call)
    const answer = handler(call) ?? { status: 404, body: apiError("not_found", `No fake for ${call.method} ${call.url.pathname}`) }
    const status = answer.status ?? 200
    return new Response(status === 204 ? null : JSON.stringify(answer.body ?? null), { status, headers: { "Content-Type": "application/json" } })
  }
  return Object.assign(impl as typeof fetch, { calls })
}
