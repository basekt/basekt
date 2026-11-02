import { describe, expect, it } from "vitest"
import { countOf, filterChoices, humanize, itemNoun } from "@/lib/fields"
import { books, callers } from "@/test/fixtures"

const choices = (resource: Parameters<typeof filterChoices>[0]) => filterChoices(resource).map(({ field, ops }) => [field.name, ops])

describe("names", () => {
  it("read as words, a reference list as the plural of what it points to", () => {
    expect(humanize("categoryId")).toBe("Category")
    expect(humanize("authorIds")).toBe("Authors")
    expect(humanize("placedAt")).toBe("Placed at")
    const field = (name: string) => ({ ...books.fields[0], name, list: true })
    expect([itemNoun(field("options")), itemNoun(field("authorIds")), itemNoun(field("categories")), itemNoun(field("glass"))]).toEqual(["option", "author", "category", "glass"])
    expect([countOf(field("options"), 1), countOf(field("options"), 4)]).toEqual(["1 option", "4 options"])
  })
})

describe("filter choices", () => {
  it("offer every operator that suits each field when the resource names none", () => {
    expect(choices(books)).toEqual([
      ["title", ["contains", "eq", "ne", "null"]],
      ["pages", ["eq", "ne", "gt", "gte", "lt", "lte", "null"]],
      ["fee", ["eq", "ne", "gt", "gte", "lt", "lte", "null"]],
      ["lendable", ["eq", "ne", "null"]],
      ["authorId", ["eq", "ne", "null"]],
    ])
  })

  it("keep to the fields and operators a resource names, and leave out the others", () => {
    expect(choices(callers)).toEqual([
      ["name", ["contains"]],
      ["line", ["eq"]],
    ])
  })
})
