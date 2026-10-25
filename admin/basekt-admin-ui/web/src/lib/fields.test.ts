import { describe, expect, it } from "vitest"
import { filterChoices } from "@/lib/fields"
import { books, callers } from "@/test/fixtures"

const choices = (resource: Parameters<typeof filterChoices>[0]) => filterChoices(resource).map(({ field, ops }) => [field.name, ops])

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
