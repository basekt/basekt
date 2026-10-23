import { screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { useState } from "react"
import { describe, expect, it } from "vitest"
import type { AdminField, FieldType, JsonValue } from "@wire"
import { fallbackWidget, fromLocalInput, toLocalInput, widgetFor, widgets } from "@/forms/widgets"
import { books, fakeFetch, librarySchema, page, tickets } from "@/test/fixtures"
import { renderInPanel } from "@/test/panel"

const fieldOf = (resource: { fields: AdminField[] }, name: string) => resource.fields.find((f) => f.name === name)!

/** Renders [field]'s widget with [initial], and returns a reader for the last value it reported. */
function renderWidget(field: AdminField, initial: JsonValue = null, fetch = fakeFetch(() => undefined)) {
  const seen: { value: JsonValue | undefined } = { value: initial }
  function Host() {
    const [value, setValue] = useState<JsonValue | undefined>(initial)
    const Widget = widgetFor(field)
    return (
      <>
        <label htmlFor="w">{field.name}</label>
        <Widget field={field} id="w" value={value} invalid={false} onChange={(next) => { seen.value = next; setValue(next) }} />
      </>
    )
  }
  renderInPanel(<Host />, librarySchema, fetch)
  return { fetch, value: () => seen.value }
}

describe("the widget registry", () => {
  it("has a widget for every field type, and a fallback for new ones", () => {
    const types: FieldType[] = ["string", "int", "long", "decimal", "bool", "instant", "enum", "reference"]
    expect(Object.keys(widgets).sort()).toEqual([...types].sort())
    expect(widgetFor({ ...fieldOf(books, "title"), type: "geopoint" as FieldType })).toBe(fallbackWidget)
  })

  it("edits text, and clears an optional field to null", async () => {
    const title = renderWidget(fieldOf(books, "title"), "Dune")
    const box = screen.getByLabelText("title")
    expect(box).toHaveAttribute("maxlength", "200")
    await userEvent.clear(box)
    await userEvent.type(box, "Emma")
    expect(title.value()).toBe("Emma")
  })

  it("sends whole numbers as numbers, and keeps text that isn't one", async () => {
    const pages = renderWidget(fieldOf(books, "pages"))
    await userEvent.type(screen.getByLabelText("pages"), "412")
    expect(pages.value()).toBe(412)
    await userEvent.type(screen.getByLabelText("pages"), "x")
    expect(pages.value()).toBe("412x")
    await userEvent.clear(screen.getByLabelText("pages"))
    expect(pages.value()).toBeNull()
  })

  it("keeps decimals as text, so no digit is lost", async () => {
    const fee = renderWidget(fieldOf(books, "fee"))
    await userEvent.type(screen.getByLabelText("fee"), "0.10000000000000000001")
    expect(fee.value()).toBe("0.10000000000000000001")
  })

  it("switches a bool", async () => {
    const lendable = renderWidget(fieldOf(books, "lendable"), false)
    await userEvent.click(screen.getByRole("switch"))
    expect(lendable.value()).toBe(true)
  })

  it("edits an instant in local time and sends UTC", () => {
    const utc = "2026-03-01T09:30:00Z"
    expect(fromLocalInput(toLocalInput(utc))).toBe(utc)
    renderWidget(fieldOf(tickets, "openedAt"), utc)
    expect(screen.getByLabelText("openedAt")).toHaveAttribute("type", "datetime-local")
  })

  it("picks an enum value", async () => {
    const state = renderWidget(fieldOf(tickets, "state"), "open")
    await userEvent.click(screen.getByRole("combobox", { name: "state" }))
    await userEvent.click(await screen.findByRole("option", { name: "closed" }))
    expect(state.value()).toBe("closed")
  })

  it("searches the referenced resource for a reference", async () => {
    const fetch = fakeFetch(({ url }) => {
      if (url.pathname === "/admin/api/resources/authors/records/a1") return { body: { id: "a1", name: "Frank Herbert" } }
      if (url.pathname === "/admin/api/resources/authors/records") {
        const all = [{ id: "a1", name: "Frank Herbert" }, { id: "a2", name: "Jane Austen" }]
        const search = url.searchParams.get("search")?.toLowerCase()
        return { body: page(all.filter((a) => !search || a.name.toLowerCase().includes(search))) }
      }
    })
    const author = renderWidget(fieldOf(books, "authorId"), "a1", fetch)
    const picker = screen.getByRole("combobox", { name: "authorId" })
    await waitFor(() => expect(picker).toHaveTextContent("Frank Herbert (a1)"))

    await userEvent.click(picker)
    await userEvent.type(await screen.findByPlaceholderText("Search…"), "jane")
    await waitFor(() => expect(fetch.calls.at(-1)!.url.searchParams.get("search")).toBe("jane"))
    await userEvent.click(await screen.findByRole("option", { name: /Jane Austen/ }))
    expect(author.value()).toBe("a2")
  })
})
