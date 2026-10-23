import { screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, expect, it } from "vitest"
import { apiError, fakeFetch, helpDeskSchema, librarySchema, page } from "@/test/fixtures"
import { renderPanel } from "@/test/render"

const dune = { id: "b1", title: "Dune", pages: 412, fee: "1.50", lendable: true, authorId: null }

describe("record forms", () => {
  it("shows validation errors under the fields they name", async () => {
    const user = userEvent.setup()
    const fetch = fakeFetch(({ url, method }) => {
      if (url.pathname === "/admin/api/schema") return { body: librarySchema }
      if (url.pathname === "/admin/api/resources/books/records" && method === "POST") {
        return {
          status: 422,
          body: apiError("validation_failed", "The record isn't valid.", [
            { field: "title", code: "required", message: "Required." },
            { field: "fee", code: "min", message: "Must be at least 0.01." },
            { field: "id", code: "read_only", message: "The id can't be set." },
          ]),
        }
      }
    })
    renderPanel("/admin/r/books/new", fetch)

    await user.type(await screen.findByLabelText("Fee*"), "0")
    await user.click(screen.getByRole("button", { name: "Create" }))

    expect(await screen.findByText("The record isn't valid.")).toBeInTheDocument()
    expect(screen.getByLabelText("Title*")).toHaveAttribute("aria-invalid", "true")
    expect(screen.getByLabelText("Title*")).toHaveAccessibleDescription("Required.")
    expect(screen.getByLabelText("Fee*")).toHaveAccessibleDescription("Must be at least 0.01.")
    expect(screen.getByLabelText("Pages")).toHaveAttribute("aria-invalid", "false")
    // An error on a field the form doesn't show is listed above the form.
    expect(screen.getByText("Id: The id can't be set.")).toBeInTheDocument()
    expect(fetch.calls.at(-1)!.body).toEqual({ fee: "0" })

    await user.type(screen.getByLabelText("Fee*"), ".5")
    expect(screen.getByLabelText("Fee*")).toHaveAttribute("aria-invalid", "false")
  })

  it("sends only what changed, and shows a hook's veto on its field", async () => {
    const user = userEvent.setup()
    const fetch = fakeFetch(({ url, method, body }) => {
      if (url.pathname === "/admin/api/schema") return { body: librarySchema }
      if (url.pathname === "/admin/api/resources/books/records/b1" && method === "GET") return { body: dune }
      if (url.pathname === "/admin/api/resources/books/records/b1" && method === "PATCH") {
        return (body as { fee?: string }).fee === "0.01"
          ? { status: 422, body: apiError("rejected", "The write was refused.", [{ field: "fee", code: "rejected", message: "Fees can't drop below cost." }]) }
          : { body: { ...dune, ...(body as object) } }
      }
    })
    renderPanel("/admin/r/books/b1", fetch)

    const fee = await screen.findByLabelText("Fee*")
    await user.clear(fee)
    await user.type(fee, "0.01")
    await user.click(screen.getByRole("button", { name: "Save" }))
    expect(await screen.findByText("Fees can't drop below cost.")).toBeInTheDocument()
    expect(fetch.calls.at(-1)!.body).toEqual({ fee: "0.01" })

    await user.clear(fee)
    await user.type(fee, "2.00")
    await user.click(screen.getByRole("button", { name: "Save" }))
    await waitFor(() => expect(screen.queryByText("Fees can't drop below cost.")).not.toBeInTheDocument())
    expect(fetch.calls.filter((c) => c.method === "PATCH").at(-1)!.body).toEqual({ fee: "2.00" })
  })

  it("hides the actions the caller may not take", async () => {
    const fetch = fakeFetch(({ url }) => {
      if (url.pathname === "/admin/api/schema") return { body: helpDeskSchema }
      if (url.pathname === "/admin/api/resources/tickets/records/t1") return { body: { id: "t1", subject: "Printer jam", state: "open", openedAt: null, views: 3 } }
      if (url.pathname === "/admin/api/resources/tickets/records") return { body: page([]) }
    })
    const { unmount } = renderPanel("/admin/r/tickets/t1", fetch)
    expect(await screen.findByText("Printer jam")).toBeInTheDocument()
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "Save" })).not.toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /delete/i })).not.toBeInTheDocument()
    unmount()

    renderPanel("/admin/r/tickets/new", fetch)
    expect(await screen.findByText("You can't create tickets")).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "Create" })).not.toBeInTheDocument()
  })

  it("deletes after confirming, when the caller may", async () => {
    const user = userEvent.setup()
    const fetch = fakeFetch(({ url, method }) => {
      if (url.pathname === "/admin/api/schema") return { body: librarySchema }
      if (url.pathname === "/admin/api/resources/books/records/b1") return method === "DELETE" ? { status: 204 } : { body: dune }
      if (url.pathname === "/admin/api/resources/books/records") return { body: page([]) }
    })
    renderPanel("/admin/r/books/b1", fetch)
    await user.click(await screen.findByRole("button", { name: /delete/i }))
    await user.click(await screen.findByRole("button", { name: "Delete" }))
    await waitFor(() => expect(location.pathname).toBe("/admin/r/books"))
    expect(fetch.calls.some((c) => c.method === "DELETE")).toBe(true)
  })
})
