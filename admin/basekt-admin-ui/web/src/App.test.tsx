import { screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, expect, it } from "vitest"
import { apiError, fakeFetch, librarySchema, page } from "@/test/fixtures"
import { renderPanel } from "@/test/render"

describe("the panel", () => {
  it("asks for a token on a 401, and sends it once accepted", async () => {
    const user = userEvent.setup()
    const fetch = fakeFetch(({ url, headers }) => {
      const bearer = headers.Authorization
      if (bearer !== undefined && bearer !== "Bearer good") return { status: 401, body: apiError("unauthorized", "Bad token.") }
      if (url.pathname === "/admin/api/schema") return bearer ? { body: librarySchema } : { status: 401, body: apiError("unauthorized", "Sign in.") }
      if (url.pathname === "/admin/api/resources/books/records") return { body: page([]) }
    })
    renderPanel("/admin/r/books", fetch)

    expect(await screen.findByText("The admin API needs you to sign in.")).toBeInTheDocument()
    await user.type(screen.getByLabelText("Access token"), "bad")
    await user.click(screen.getByRole("button", { name: "Sign in" }))
    expect(await screen.findByText("That token was refused.")).toBeInTheDocument()

    await user.clear(screen.getByLabelText("Access token"))
    await user.type(screen.getByLabelText("Access token"), "good")
    await user.click(screen.getByRole("button", { name: "Sign in" }))
    expect(await screen.findByText("No records yet.")).toBeInTheDocument()
    expect(fetch.calls.at(-1)!.headers.Authorization).toBe("Bearer good")
    expect(screen.getByText("librarian")).toBeInTheDocument()

    await user.click(screen.getByRole("button", { name: /sign out/i }))
    expect(await screen.findByText("The admin API needs you to sign in.")).toBeInTheDocument()
  })

  it("refuses a schema version it doesn't speak", async () => {
    const fetch = fakeFetch(({ url }) => (url.pathname === "/admin/api/schema" ? { body: { ...librarySchema, version: 2 } } : undefined))
    renderPanel("/admin/", fetch)
    expect(await screen.findByText("This panel doesn't match the admin API")).toBeInTheDocument()
  })

  it("says when a resource isn't in the caller's schema", async () => {
    const fetch = fakeFetch(({ url }) => (url.pathname === "/admin/api/schema" ? { body: librarySchema } : undefined))
    renderPanel("/admin/r/payroll", fetch)
    expect(await screen.findByText("No Payroll here")).toBeInTheDocument()
  })
})
