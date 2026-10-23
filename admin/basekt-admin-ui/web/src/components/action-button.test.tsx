import { screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, expect, it } from "vitest"
import type { AdminSchema } from "@wire"
import { apiError, fakeFetch, librarySchema, page } from "@/test/fixtures"
import { renderPanel } from "@/test/render"

const dune = { id: "b1", title: "Dune", pages: 412, fee: "1.50", lendable: true, authorId: null }

/** The library, with a record action that needs a borrower and a resource action with no input. */
const withActions: AdminSchema = {
  ...librarySchema,
  resources: librarySchema.resources.map((r) => r.name !== "books" ? r : {
    ...r,
    actions: [
      {
        name: "lend",
        target: "record",
        input: [{ name: "borrower", type: "string", required: true, values: null, target: null, rules: [], bucket: null, scopedValues: null, auto: null }],
        confirm: "Lend this book?",
      },
      { name: "recount", target: "resource", input: [], confirm: null },
    ],
  }),
}

describe("actions", () => {
  it("asks for a record action's input, shows its errors, then runs it", async () => {
    const user = userEvent.setup()
    const fetch = fakeFetch(({ url, method, body }) => {
      if (url.pathname === "/admin/api/schema") return { body: withActions }
      if (url.pathname === "/admin/api/resources/books/records/b1" && method === "GET") return { body: dune }
      if (url.pathname === "/admin/api/resources/books/records/b1/actions/lend") {
        return (body as { borrower?: string }).borrower
          ? { body: dune }
          : { status: 422, body: apiError("validation_failed", "The input has invalid fields.", [{ field: "borrower", code: "required", message: "'borrower' is required." }]) }
      }
    })
    renderPanel("/admin/r/books/b1", fetch)

    await user.click(await screen.findByRole("button", { name: "Lend" }))
    expect(screen.getByText("Lend this book?")).toBeInTheDocument()
    const dialog = screen.getByRole("form", { name: "Lend" })
    await user.click(screen.getAllByRole("button", { name: "Lend" }).at(-1)!)
    expect(await screen.findByText("'borrower' is required.")).toBeInTheDocument()
    expect(dialog).toBeInTheDocument()

    await user.type(screen.getByLabelText("Borrower*"), "Ana")
    await user.click(screen.getAllByRole("button", { name: "Lend" }).at(-1)!)
    await waitFor(() => expect(screen.queryByRole("form", { name: "Lend" })).not.toBeInTheDocument())
    expect(fetch.calls.filter((c) => c.url.pathname.endsWith("/actions/lend")).at(-1)!.body).toEqual({ borrower: "Ana" })
  })

  it("runs a resource action without input straight from the list", async () => {
    const user = userEvent.setup()
    const fetch = fakeFetch(({ url, method }) => {
      if (url.pathname === "/admin/api/schema") return { body: withActions }
      if (url.pathname === "/admin/api/resources/books/records" && method === "GET") return { body: page([dune]) }
      if (url.pathname === "/admin/api/resources/books/actions/recount" && method === "POST") return { status: 204 }
    })
    renderPanel("/admin/r/books", fetch)

    await user.click(await screen.findByRole("button", { name: "Recount" }))
    await waitFor(() => expect(fetch.calls.some((c) => c.url.pathname === "/admin/api/resources/books/actions/recount")).toBe(true))
    expect(screen.queryByRole("button", { name: "Lend" })).not.toBeInTheDocument()
  })
})
