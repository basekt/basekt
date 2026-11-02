import { describe, expect, it } from "vitest"
import type { AdminField, AdminResource } from "@wire"
import { ApiFailure } from "@/api/client"
import { errorsFrom } from "@/forms/record-form"
import { apiError, books } from "@/test/fixtures"

const fieldOf = (resource: { fields: AdminField[] }, name: string) => resource.fields.find((f) => f.name === name)!

// A made-up quiz: a card's choices are an object list, its clues a plain list.
const cards: AdminResource = {
  ...books,
  name: "cards",
  fields: [
    { ...fieldOf(books, "title"), name: "prompt" },
    {
      ...fieldOf(books, "title"), name: "choices", type: "object", list: true, itemIds: true, rules: [],
      fields: [{ ...fieldOf(books, "title"), name: "text", rules: [] }],
    },
    { ...fieldOf(books, "title"), name: "clues", list: true },
  ],
}

describe("errors on list items", () => {
  it("go under their list and say which item and field they are about", () => {
    const failure = new ApiFailure(422, apiError("validation_failed", "The record has invalid fields.", [
      { field: "choices.1.text", code: "required", message: "'text' is required." },
      { field: "clues.0", code: "length", message: "Must be at most 20 characters long." },
      { field: "prompt", code: "required", message: "'prompt' is required." },
      { field: "nowhere.0", code: "custom", message: "No such field." },
    ]))
    const errors = errorsFrom(cards, failure)
    expect(errors.fields.choices.map((e) => e.message)).toEqual(["Choice 2, text: 'text' is required."])
    expect(errors.fields.clues.map((e) => e.message)).toEqual(["Clue 1: Must be at most 20 characters long."])
    expect(errors.fields.prompt.map((e) => e.message)).toEqual(["'prompt' is required."])
    expect(errors.form.map((e) => e.field)).toEqual(["nowhere.0"])
  })
})
