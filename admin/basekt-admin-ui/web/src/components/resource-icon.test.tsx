import { render, screen, waitFor, within } from "@testing-library/react"
import { BookOpen, Table2 } from "lucide-react"
import { afterEach, describe, expect, it, vi } from "vitest"
import { ResourceIcon } from "@/components/resource-icon"
import { authors, books, fakeFetch, librarySchema, page } from "@/test/fixtures"
import { renderPanel } from "@/test/render"

/** The drawing of a Lucide icon: what tells two icons apart, whatever classes they are given. */
function drawing(element: Element | null) {
  return element?.querySelector("svg")?.innerHTML ?? element?.innerHTML
}
const iconOf = (Icon: typeof Table2) => drawing(render(<span><Icon /></span>).container.firstElementChild)

afterEach(() => vi.restoreAllMocks())

describe("resource icons", () => {
  it("shows each resource's own Lucide icon in the menu and on the home screen, and a table for one without", async () => {
    const schema = {
      ...librarySchema,
      resources: [{ ...books, view: { ...books.view!, icon: "book-open" } }, authors],
    }
    const fetchImpl = fakeFetch(({ url }) => {
      if (url.pathname === "/admin/api/schema") return { body: schema }
      if (url.pathname.endsWith("/records")) return { body: page([]) }
    })
    renderPanel("/admin/", fetchImpl)

    const menu = await screen.findByRole("navigation", { name: "Resources" })
    const bookOpen = iconOf(BookOpen)
    const table = iconOf(Table2)
    await waitFor(() => expect(drawing(within(menu).getByRole("link", { name: "Books" }))).toBe(bookOpen))
    expect(drawing(within(menu).getByRole("link", { name: "Authors" }))).toBe(table)

    const cards = screen.getAllByRole("link").filter((link) => link.closest("main"))
    await waitFor(() => expect(drawing(cards.find((card) => card.textContent?.includes("Books"))!)).toBe(bookOpen))
    expect(drawing(cards.find((card) => card.textContent?.includes("Authors"))!)).toBe(table)
  })

  it("shows a table for a name Lucide doesn't have, and names it in the console once", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {})
    const table = iconOf(Table2)

    const first = render(<span><ResourceIcon name="graduation-hat" /></span>)
    const second = render(<span><ResourceIcon name="graduation-hat" /></span>)

    await waitFor(() => expect(drawing(first.container.firstElementChild)).toBe(table))
    await waitFor(() => expect(drawing(second.container.firstElementChild)).toBe(table))
    expect(warn).toHaveBeenCalledTimes(1)
    expect(warn.mock.calls[0][0]).toContain("'graduation-hat'")
  })

  it("finds every icon by the name lucide.dev gives it", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {})
    const table = iconOf(Table2)
    // Names with digits and several words, the ones a conversion to component names could get wrong, and an old name.
    for (const name of ["gamepad-2", "building-2", "building-complex", "arrow-down-0-1", "circle-question-mark", "graduation-cap"]) {
      const shown = render(<span><ResourceIcon name={name} /></span>)
      await waitFor(() => expect(shown.container.querySelector("svg")).not.toBeNull())
      expect(drawing(shown.container.firstElementChild), name).not.toBe(table)
    }
    // Every name Lucide knows, old ones included, and nothing that isn't an icon.
    const { iconNames } = await import("lucide-react/dynamic")
    const { lucideIcon } = await import("@/lib/lucide-icons")
    expect(iconNames.filter((name) => !lucideIcon(name))).toEqual([])
    expect(lucideIcon("icon")).toBeUndefined()
    expect(lucideIcon("lucide-provider")).toBeUndefined()
    expect(warn).not.toHaveBeenCalled()
  })
})
