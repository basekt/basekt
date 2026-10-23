import { describe, expect, it } from "vitest"
import { menuSections } from "@/lib/menu"
import { authors, books } from "@/test/fixtures"

describe("menuSections", () => {
  it("puts ungrouped resources first, then groups in declaration order", () => {
    const stock = { ...books, name: "stock", group: "Inventory" }
    const sales = { ...books, name: "sales", group: "Sales" }
    const more = { ...books, name: "units", group: "Inventory" }
    const sections = menuSections([stock, sales, authors, more])
    expect(sections.map((s) => s.group)).toEqual([null, "Inventory", "Sales"])
    expect(sections[1].resources.map((r) => r.name)).toEqual(["stock", "units"])
  })

  it("is one section without a heading when nothing is grouped", () => {
    expect(menuSections([books, authors]).map((s) => s.group)).toEqual([null])
  })
})
