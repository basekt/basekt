import { screen, waitFor, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, expect, it } from "vitest"
import { authors, books, fakeFetch, librarySchema, page } from "@/test/fixtures"
import { renderPanel } from "@/test/render"

// Books under a heading and authors at the top: a narrow screen used to hide the headings.
const schema = { ...librarySchema, resources: [{ ...books, group: "Catalog" }, authors] }

const serve = () =>
  fakeFetch(({ url }) => {
    if (url.pathname === "/admin/api/schema") return { body: schema }
    if (url.pathname.endsWith("/records")) return { body: page([]) }
  })

describe("the menu on a narrow screen", () => {
  it("opens as a drawer with every heading, and closes on the screen picked", async () => {
    const user = userEvent.setup()
    renderPanel("/admin/", serve())

    await user.click(await screen.findByRole("button", { name: "Open the menu" }))
    const drawer = await screen.findByRole("dialog", { name: "Menu" })
    const menu = within(drawer).getByRole("navigation", { name: "Resources" })
    expect(within(menu).getByRole("heading", { name: "Catalog" })).toBeTruthy()
    expect(within(menu).getByRole("link", { name: "Authors" })).toBeTruthy()
    expect(within(drawer).getByText("librarian")).toBeTruthy()

    await user.click(within(menu).getByRole("link", { name: "Books" }))
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Menu" })).toBeNull())
    expect(location.pathname).toBe("/admin/r/books")
  })
})
