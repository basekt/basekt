import { screen, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { useState } from "react"
import { describe, expect, it } from "vitest"
import type { AdminField, JsonValue, ListStyle } from "@wire"
import { widgetFor } from "@/forms/widgets"
import { books, fakeFetch, librarySchema } from "@/test/fixtures"
import { renderInPanel } from "@/test/panel"

const fieldOf = (name: string) => books.fields.find((f) => f.name === name)!

// A made-up quiz: a card's choices, each a text and a flag for the right one.
const choices: AdminField = {
  ...fieldOf("title"), name: "choices", type: "object", list: true, required: true, itemIds: true, rules: [],
  fields: [
    { ...fieldOf("title"), name: "text", required: true, rules: [] },
    { ...fieldOf("lendable"), name: "isRight" },
  ],
}
const four = [
  { id: "c1", text: "Mars", isRight: false },
  { id: "c2", text: "**Jupiter**", isRight: true },
  { id: "c3", text: "Venus", isRight: false },
]

function renderList(listStyle: ListStyle, initial: JsonValue = four) {
  const seen: { value: JsonValue | undefined } = { value: initial }
  function Host() {
    const [value, setValue] = useState<JsonValue | undefined>(initial)
    const Widget = widgetFor(choices)
    return <Widget field={choices} id="w" value={value} invalid={false} listStyle={listStyle} onChange={(next) => { seen.value = next; setValue(next) }} />
  }
  renderInPanel(<Host />, librarySchema, fakeFetch(() => undefined))
  return { value: () => seen.value }
}

describe("list styles", () => {
  it("rows put each item on one line, with every item in view", () => {
    renderList("rows")
    const rows = screen.getAllByRole("listitem")
    expect(rows).toHaveLength(3)
    expect(within(rows[1]).getByDisplayValue("**Jupiter**")).toBeInTheDocument()
    expect(within(rows[1]).getByRole("switch")).toBeChecked()
    expect(within(rows[1]).getByText("Is right")).toBeVisible()
    expect(screen.getByRole("button", { name: "Add choice" })).toBeInTheDocument()
  })

  it("tabs show one item at a time, name each tab by its text with a check for the right one, and add one with +", async () => {
    const user = userEvent.setup()
    const list = renderList("tabs")
    const tabs = screen.getAllByRole("tab")
    expect(tabs.map((t) => t.textContent)).toEqual(["Mars", "Jupiter", "Venus"])
    expect(within(tabs[1]).getByLabelText("checked")).toBeInTheDocument()
    expect(tabs[0]).toHaveAttribute("aria-selected", "true")
    expect(screen.getByDisplayValue("Mars")).toBeInTheDocument()
    expect(screen.queryByDisplayValue("Venus")).not.toBeInTheDocument()

    await user.click(tabs[2])
    expect(screen.getByDisplayValue("Venus")).toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "Move choice 3 left" }))
    expect((list.value() as { id: string }[]).map((c) => c.id)).toEqual(["c1", "c3", "c2"])
    expect(screen.getAllByRole("tab")[1]).toHaveAttribute("aria-selected", "true")

    await user.click(screen.getByRole("button", { name: "Add choice" }))
    expect(screen.getAllByRole("tab")).toHaveLength(4)
    expect(screen.getAllByRole("tab")[3]).toHaveTextContent("Choice 4")
    expect(screen.getAllByRole("tab")[3]).toHaveAttribute("aria-selected", "true")
    await user.type(screen.getByRole("tabpanel").querySelector("input")!, "Saturn")
    expect(screen.getAllByRole("tab")[3]).toHaveTextContent("Saturn")

    await user.click(screen.getByRole("button", { name: "Remove choice 4" }))
    expect(screen.getAllByRole("tab")).toHaveLength(3)
    expect(screen.getAllByRole("tab")[2]).toHaveAttribute("aria-selected", "true")
  })

  it("tabs say so when there is nothing yet", () => {
    renderList("tabs", [])
    expect(screen.getByText("No choices yet.")).toBeInTheDocument()
    expect(screen.queryByRole("tabpanel")).not.toBeInTheDocument()
  })
})
