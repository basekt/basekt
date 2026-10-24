import { screen, within } from "@testing-library/react"
import { describe, expect, it } from "vitest"
import { books, config, fakeFetch, librarySchema, page } from "@/test/fixtures"
import { renderPanel } from "@/test/render"

const linked = {
  ...books,
  links: [
    { label: "View on site", url: "/books/{id}" },
    { label: "Preview", url: "/preview?title={title}" },
  ],
}
const schema = { ...librarySchema, resources: [linked, librarySchema.resources[1]] }

const dune = { id: "b1", title: "Dune & Co", pages: 412, fee: "1.50", lendable: true, authorId: null }
const untitled = { id: "b2", title: null, pages: 10, fee: "0", lendable: false, authorId: null }

const serve = (rows = [dune, untitled]) =>
  fakeFetch(({ url }) => {
    if (url.pathname === "/admin/api/schema") return { body: schema }
    if (url.pathname === "/admin/api/resources/books/records") return { body: page(rows) }
    const one = rows.find((row) => url.pathname === `/admin/api/resources/books/records/${row.id}`)
    if (one) return { body: one }
  })

describe("links to the host app's pages", () => {
  it("shows them on a record's page, filled from the record and opening in a new tab", async () => {
    renderPanel("/admin/r/books/b1", serve())

    const view = await screen.findByRole("link", { name: /View on site/ })
    expect(view.getAttribute("href")).toBe("/books/b1")
    expect(view.getAttribute("target")).toBe("_blank")
    expect(view.getAttribute("rel")).toBe("noopener noreferrer")
    expect(screen.getByRole("link", { name: /Preview/ }).getAttribute("href")).toBe("/preview?title=Dune%20%26%20Co")
  })

  it("leaves out a link whose value the record doesn't have", async () => {
    renderPanel("/admin/r/books/b2", serve())

    expect((await screen.findByRole("link", { name: /View on site/ })).getAttribute("href")).toBe("/books/b2")
    expect(screen.queryByRole("link", { name: /Preview/ })).toBeNull()
  })

  it("puts an icon link on each row of the list", async () => {
    renderPanel("/admin/r/books", serve())

    const table = await screen.findByRole("table")
    const first = within(await within(table).findByRole("row", { name: /Dune/ }))
    expect(first.getByRole("link", { name: "View on site: b1" }).getAttribute("href")).toBe("/books/b1")
    expect(first.getByRole("link", { name: "Preview: b1" }).getAttribute("href")).toBe("/preview?title=Dune%20%26%20Co")
    const second = within(screen.getAllByRole("row")[2]!)
    expect(second.getByRole("link", { name: "View on site: b2" })).toBeTruthy()
    expect(second.queryByRole("link", { name: /Preview/ })).toBeNull()
  })

  it("adds nothing to a resource without links", async () => {
    renderPanel("/admin/r/authors", fakeFetch(({ url }) => {
      if (url.pathname === "/admin/api/schema") return { body: schema }
      if (url.pathname === "/admin/api/resources/authors/records") return { body: page([{ id: "a1", name: "Herbert" }]) }
    }))
    const table = await screen.findByRole("table")
    expect(await within(table).findByText("Herbert")).toBeTruthy()
    expect(within(table).queryAllByRole("link", { name: /site|Preview/ })).toEqual([])
    expect(within(table).queryByRole("columnheader", { name: "Links" })).toBeNull()
  })
})

describe("the site link in the top bar", () => {
  const withSite = { ...config, siteLink: { label: "Open site", url: "/" } }

  it("opens the host's site in a new tab", async () => {
    renderPanel("/admin", serve(), null, withSite)
    const link = await screen.findByRole("link", { name: /Open site/ })
    expect(link.getAttribute("href")).toBe("/")
    expect(link.getAttribute("target")).toBe("_blank")
  })

  it("is absent unless the plugin was given one, and never follows an unsafe address", async () => {
    renderPanel("/admin", serve())
    await screen.findAllByRole("link")
    expect(screen.queryByRole("link", { name: /Open site/ })).toBeNull()
  })

  it("won't show an address that isn't a path or an http address", async () => {
    renderPanel("/admin", serve(), null, { ...config, siteLink: { label: "Open site", url: "javascript:alert(1)" } })
    await screen.findAllByRole("link")
    expect(screen.queryByRole("link", { name: /Open site/ })).toBeNull()
  })
})
