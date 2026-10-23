import { screen, waitFor, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, expect, it } from "vitest"
import { apiError, fakeFetch, helpDeskSchema, librarySchema, page } from "@/test/fixtures"
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
    expect(headers).toEqual(["ID", "Title", "Pages", "Fee", "Lendable", "Author"])
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
})
