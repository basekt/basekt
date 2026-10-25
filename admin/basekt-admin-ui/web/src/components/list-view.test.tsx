import { screen, waitFor, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, expect, it, vi } from "vitest"
import { apiError, fakeFetch, helpDeskSchema, librarySchema, page, switchboardSchema } from "@/test/fixtures"
import { renderPanel } from "@/test/render"

const library = [
  { id: "b1", title: "Dune", pages: 412, fee: "1.50", lendable: true, authorId: "a1" },
  { id: "b2", title: "Emma", pages: 320, fee: "0.75", lendable: false, authorId: null },
]

describe("the list view", () => {
  it("renders columns, rows and the total from the schema", async () => {
    const fetch = fakeFetch(({ url }) => {
      if (url.pathname === "/admin/api/schema") return { body: librarySchema }
      if (url.pathname === "/admin/api/resources/books/records") return { body: page(library, 7, "next-1") }
    })
    renderPanel("/admin/r/books", fetch)

    const table = await screen.findByRole("table")
    const headers = within(table).getAllByRole("columnheader").map((h) => h.textContent)
    expect(headers).toEqual(["", "ID", "Title", "Pages", "Fee", "Lendable", "Author"])
    expect(await within(table).findByText("Dune")).toBeInTheDocument()
    expect(within(table).getByRole("link", { name: "a1" })).toHaveAttribute("href", "/admin/r/authors/a1")
    expect(within(table).getByText("No")).toBeInTheDocument()
    expect(screen.getByText("1–2 of 7")).toBeInTheDocument()
    expect(screen.getByRole("link", { name: /new/i })).toHaveAttribute("href", "/admin/r/books/new")
    expect(screen.getByRole("navigation", { name: "Resources" })).toHaveTextContent("BooksAuthors")
  })

  it("pages with the cursor, and searches, sorts and filters through the URL", async () => {
    const user = userEvent.setup()
    const fetch = fakeFetch(({ url }) => {
      if (url.pathname === "/admin/api/schema") return { body: librarySchema }
      if (url.pathname !== "/admin/api/resources/books/records") return undefined
      return url.searchParams.get("cursor") === "next-1" ? { body: page([library[1]], 3) } : { body: page([library[0]], 3, "next-1") }
    })
    renderPanel("/admin/r/books", fetch)

    await user.click(await screen.findByRole("button", { name: /next/i }))
    expect(await screen.findByText("2–2 of 3")).toBeInTheDocument()
    expect(fetch.calls.at(-1)!.url.searchParams.get("cursor")).toBe("next-1")

    await user.type(screen.getByRole("searchbox", { name: "Search" }), "dune{Enter}")
    await waitFor(() => expect(fetch.calls.at(-1)!.url.searchParams.get("search")).toBe("dune"))
    expect(fetch.calls.at(-1)!.url.searchParams.has("cursor")).toBe(false)
    expect(location.search).toBe("?search=dune")

    await user.click(screen.getByRole("button", { name: "Fee" }))
    await waitFor(() => expect(fetch.calls.at(-1)!.url.searchParams.get("sort")).toBe("fee"))
    await user.click(screen.getByRole("button", { name: "Fee" }))
    await waitFor(() => expect(fetch.calls.at(-1)!.url.searchParams.get("sort")).toBe("-fee"))
    expect(screen.getByRole("columnheader", { name: "Fee" })).toHaveAttribute("aria-sort", "descending")
  })

  it("leaves out create for a read-only caller, and shows enums and instants", async () => {
    const fetch = fakeFetch(({ url }) => {
      if (url.pathname === "/admin/api/schema") return { body: helpDeskSchema }
      if (url.pathname === "/admin/api/resources/tickets/records") {
        return { body: page([{ id: "t1", subject: "Printer jam", state: "waiting", openedAt: "2026-03-01T09:30:00Z", views: 3 }], null) }
      }
    })
    renderPanel("/admin/r/tickets", fetch)

    expect(await screen.findByText("Printer jam")).toBeInTheDocument()
    expect(screen.getByText("waiting")).toHaveAttribute("data-slot", "badge")
    expect(screen.queryByRole("link", { name: /new/i })).not.toBeInTheDocument()
    expect(screen.getByText("1–1")).toBeInTheDocument()
  })

  it("explains a forbidden list and an invalid query", async () => {
    let answer = { status: 403, body: apiError("forbidden", "Your roles can't read 'books'.") }
    const fetch = fakeFetch(({ url }) => {
      if (url.pathname === "/admin/api/schema") return { body: librarySchema }
      if (url.pathname === "/admin/api/resources/books/records") return answer
    })
    renderPanel("/admin/r/books", fetch)
    expect(await screen.findByText("You don't have access")).toBeInTheDocument()
    expect(screen.getByText("Your roles can't read 'books'.")).toBeInTheDocument()

    answer = { status: 400, body: apiError("invalid_query", "Unknown field 'colour'.") }
    await userEvent.click(screen.getByRole("link", { name: "Authors" }))
    await userEvent.click(screen.getByRole("link", { name: "Books" }))
    expect(await screen.findByText("Unknown field 'colour'.")).toBeInTheDocument()
  })

  it("bulk deletes the selection and lists what the API refused", async () => {
    const user = userEvent.setup()
    let rows = library
    const fetch = fakeFetch(({ url, body }) => {
      if (url.pathname === "/admin/api/schema") return { body: librarySchema }
      if (url.pathname === "/admin/api/resources/books/records") return { body: page(rows) }
      if (url.pathname === "/admin/api/resources/books/bulk-delete") {
        rows = rows.filter((r) => r.id !== "b1")
        expect(body).toEqual({ ids: ["b1", "b2"] })
        return { body: { deleted: ["b1"], failed: [{ id: "b2", error: apiError("rejected", "Emma is on loan.") }] } }
      }
    })
    renderPanel("/admin/r/books", fetch)

    await user.click(await screen.findByRole("checkbox", { name: "Select all on this page" }))
    expect(screen.getByRole("toolbar", { name: "Selected records" })).toHaveTextContent("2 selected")
    await user.click(within(screen.getByRole("toolbar")).getByRole("button", { name: /delete/i }))
    await user.click(await screen.findByRole("button", { name: "Delete" }))

    expect(await screen.findByText("1 not deleted")).toBeInTheDocument()
    expect(screen.getByText(/Emma is on loan\./)).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText("Dune")).not.toBeInTheDocument())
    expect(screen.getByRole("checkbox", { name: "Select b2" })).toBeChecked()
  })

  it("lists a read-only resource with no edit controls, and offers only the filters and sorts it takes", async () => {
    const user = userEvent.setup()
    const fetch = fakeFetch(({ url }) => {
      if (url.pathname === "/admin/api/schema") return { body: switchboardSchema }
      if (url.pathname === "/admin/api/resources/callers/records") return { body: page([{ id: "c1", name: "Ana", line: "sales", waitedSeconds: 40 }], null) }
    })
    renderPanel("/admin/r/callers", fetch)

    expect(await screen.findByText("Ana")).toBeInTheDocument()
    expect(screen.getByText("Read-only")).toHaveAttribute("data-slot", "badge")
    expect(screen.queryByRole("link", { name: /new/i })).not.toBeInTheDocument()
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Waited seconds" })).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "Name" })).not.toBeInTheDocument()
    expect(screen.getByRole("searchbox", { name: "Search" })).toBeInTheDocument()

    await user.click(screen.getByRole("button", { name: /filter/i }))
    const form = screen.getByRole("form", { name: "New filter" })
    expect(within(form).getByRole("combobox", { name: "Field" })).toHaveTextContent("Name")
    expect(within(form).getByRole("combobox", { name: "Operator" })).toHaveTextContent("contains")
  })

  it("exports CSV with the list's parameters, and offers no selection without delete", async () => {
    const fetch = fakeFetch(({ url }) => {
      if (url.pathname === "/admin/api/schema") return { body: helpDeskSchema }
      if (url.pathname === "/admin/api/resources/tickets/records") return { body: page([{ id: "t1", subject: "Printer jam", state: "open", openedAt: null, views: 1 }]) }
      if (url.pathname === "/admin/api/resources/tickets/export.csv") return { body: "id,subject" }
    })
    URL.createObjectURL = vi.fn(() => "blob:csv")
    URL.revokeObjectURL = vi.fn()
    renderPanel("/admin/r/tickets?state=open&sort=-openedAt", fetch)

    expect(await screen.findByText("Printer jam")).toBeInTheDocument()
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole("button", { name: /export csv/i }))
    await waitFor(() => expect(URL.createObjectURL).toHaveBeenCalled())
    const exported = fetch.calls.find((c) => c.url.pathname.endsWith("export.csv"))!
    expect([...exported.url.searchParams]).toEqual([["state", "open"], ["sort", "-openedAt"]])
  })
})
