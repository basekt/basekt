import { screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, describe, expect, it, vi } from "vitest"
import { AdminApi, ApiFailure, FailureLog } from "@/api/client"
import { apiError, fakeFetch, helpDeskSchema, librarySchema } from "@/test/fixtures"
import { renderPanel } from "@/test/render"

afterEach(() => vi.restoreAllMocks())

describe("a failed call", () => {
  it("that got no answer says the server couldn't be reached, and copies details for a report", async () => {
    const user = userEvent.setup()
    const writeText = vi.fn(async (_: string) => {})
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true })
    const answers = fakeFetch(({ url }) => (url.pathname === "/admin/api/schema" ? { body: librarySchema } : undefined))
    const fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).includes("/records")) throw new TypeError("Failed to fetch")
      return answers(input, init)
    }) as typeof globalThis.fetch
    renderPanel("/admin/r/books", fetch)

    expect(await screen.findByText("Couldn't reach the server")).toBeInTheDocument()
    expect(screen.queryByText(/Request id/)).not.toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "Copy details" }))
    const report = writeText.mock.calls[0][0]
    expect(report).toContain("Call: GET /admin/api/resources/books/records")
    expect(report).toContain("Answer: none: the server couldn't be reached")
    expect(report).toContain("Request id: none")
    expect(report).toContain("Screen: /admin/r/books")
    expect(report).not.toContain("Bearer")
  })

  it("that the server answered shows its request id, and downloads the log of recent failures", async () => {
    const user = userEvent.setup()
    const blobs: Blob[] = []
    vi.spyOn(URL, "createObjectURL").mockImplementation((blob) => {
      blobs.push(blob as Blob)
      return "blob:log"
    })
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {})
    const clicked = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {})
    const fetch = fakeFetch(({ url }) => {
      if (url.pathname === "/admin/api/schema") return { body: helpDeskSchema }
      return { status: 500, body: { ...apiError("internal_error", "Something went wrong on the server. Quote request id r-77 to report it."), requestId: "r-77" } }
    })
    renderPanel("/admin/r/tickets", fetch)

    expect(await screen.findByText("Something went wrong")).toBeInTheDocument()
    expect(screen.getByText("r-77")).toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "Download log" }))
    expect(clicked).toHaveBeenCalled()
    const log = JSON.parse(await blobs[0].text())
    expect(log.failures[0]).toMatchObject({ method: "GET", status: 500, error: "internal_error", requestId: "r-77" })
    expect(log.failures[0].path).toContain("/admin/api/resources/tickets/records")
  })
})

describe("FailureLog", () => {
  it("keeps the newest failures first, up to its size, with the header's request id when the body has none", async () => {
    const log = new FailureLog(2)
    const fetch = fakeFetch(() => ({ status: 503, body: null }))
    const api = new AdminApi("/admin/api", () => null, fetch, log)
    for (const name of ["a", "b", "c"]) await api.get("books", name).catch(() => {})
    expect(log.entries().map((e) => e.path)).toEqual(["/admin/api/resources/books/records/c", "/admin/api/resources/books/records/b"])

    const answered = new Response(JSON.stringify(null), { status: 502, headers: { "X-Request-Id": "edge-9" } })
    const failure = await new AdminApi("/admin/api", () => null, (async () => answered) as typeof globalThis.fetch).schema().catch((e: unknown) => e)
    expect(failure).toBeInstanceOf(ApiFailure)
    expect((failure as ApiFailure).requestId).toBe("edge-9")
  })
})
