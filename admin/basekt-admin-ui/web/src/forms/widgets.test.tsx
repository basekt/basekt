import { screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { useState } from "react"
import { describe, expect, it, vi } from "vitest"
import type { AdminField, FieldType, JsonValue } from "@wire"
import { fallbackWidget, fromLocalInput, toLocalInput, widgetFor, widgets } from "@/forms/widgets"
import { books, fakeFetch, librarySchema, page, tickets } from "@/test/fixtures"
import { renderInPanel } from "@/test/panel"

const fieldOf = (resource: { fields: AdminField[] }, name: string) => resource.fields.find((f) => f.name === name)!

/** Renders [field]'s widget with [initial], and returns a reader for the last value it reported. */
function renderWidget(field: AdminField, initial: JsonValue = null, fetch = fakeFetch(() => undefined), xhr?: () => XMLHttpRequest) {
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
  renderInPanel(<Host />, librarySchema, fetch, xhr)
  return { fetch, value: () => seen.value }
}

describe("a Markdown field", () => {
  const body: AdminField = {
    name: "body", type: "string", required: false, values: null, target: null, rules: [], bucket: null, scopedValues: null, auto: null, list: false,
    markdown: { math: true, images: false, maxLength: 500 },
  }

  it("is a roomy text box that keeps the text as typed and shows its limit", async () => {
    const { value } = renderWidget(body, "Solve $x$")
    const box = screen.getByLabelText("body") as HTMLTextAreaElement
    expect(box.tagName).toBe("TEXTAREA")
    expect(box.maxLength).toBe(500)
    expect(screen.getByText(/Markdown, with formulas/)).toBeTruthy()
    expect(screen.getByText(/9 of 500 characters/)).toBeTruthy()
    await userEvent.type(box, "!")
    expect(value()).toBe("Solve $x$!")
  })

  it("sends null when an optional field is emptied", async () => {
    const { value } = renderWidget(body, "a")
    await userEvent.clear(screen.getByLabelText("body"))
    expect(value()).toBeNull()
  })

  it("says nothing of formulas when they are off", () => {
    renderWidget({ ...body, markdown: { math: false, images: false, maxLength: 100 } }, "")
    expect(screen.queryByText(/formulas/)).toBeNull()
  })
})

describe("the widget registry", () => {
  it("has a widget for every field type, and a fallback for new ones", () => {
    const types: FieldType[] = ["string", "int", "long", "decimal", "bool", "instant", "enum", "reference", "file"]
    expect(Object.keys(widgets).sort()).toEqual([...types].sort())
    expect(widgetFor({ ...fieldOf(books, "title"), type: "geopoint" as FieldType })).toBe(fallbackWidget)
  })

  it("edits a list one item per line, with numbers for integer fields", async () => {
    const tags = renderWidget({ ...fieldOf(books, "title"), list: true }, null)
    await userEvent.type(screen.getByLabelText("title"), "a{Enter}b")
    expect(tags.value()).toEqual(["a", "b"])
  })

  it("sends the items of an integer list as numbers", async () => {
    const loads = renderWidget({ ...fieldOf(books, "pages"), list: true }, null)
    await userEvent.type(screen.getByLabelText("pages"), "3{Enter}x")
    expect(loads.value()).toEqual([3, "x"])
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

  it("uploads a file to the field's bucket with progress, and keeps its key", async () => {
    const screenshot = fieldOf(tickets, "screenshot")
    const sent: { url?: string; body?: FormData } = {}
    let finish: (status: number, body: unknown) => void = () => {}
    const xhr = () => {
      const listeners: Record<string, () => void> = {}
      const progress: ((event: { lengthComputable: boolean; loaded: number; total: number }) => void)[] = []
      const request = {
        status: 0,
        responseText: "",
        withCredentials: false,
        upload: { addEventListener: (_: string, listener: (typeof progress)[number]) => progress.push(listener) },
        open: (_method: string, url: string) => { sent.url = url },
        setRequestHeader: () => {},
        addEventListener: (type: string, listener: () => void) => { listeners[type] = listener },
        send: (body: FormData) => {
          sent.body = body
          progress.forEach((listener) => listener({ lengthComputable: true, loaded: 5, total: 10 }))
        },
      }
      finish = (status, body) => {
        request.status = status
        request.responseText = JSON.stringify(body)
        listeners.load()
      }
      return request as unknown as XMLHttpRequest
    }
    const shot = renderWidget(screenshot, null, fakeFetch(() => undefined), xhr)

    await userEvent.upload(screen.getByLabelText("screenshot"), new File(["png"], "jam.png", { type: "image/png" }))
    expect(sent.url).toBe("/files/screens")
    expect((sent.body!.get("file") as File).name).toBe("jam.png")
    expect(await screen.findByRole("progressbar", { name: "Uploading" })).toHaveAttribute("aria-valuenow", "50")

    const ref = { key: "screens/ab12/jam.png", bucket: "screens", name: "jam.png", size: 3, contentType: "image/png", url: "/files/screens/ab12/jam.png" }
    finish(201, ref)
    await waitFor(() => expect(shot.value()).toBe("screens/ab12/jam.png"))
    expect(screen.queryByRole("progressbar")).not.toBeInTheDocument()
    expect(screen.getByRole("button", { name: /replace/i })).toBeInTheDocument()

    await userEvent.upload(screen.getByLabelText("screenshot"), new File(["<html>"], "page.png", { type: "image/png" }))
    finish(415, { error: "unsupported_type", message: "'screens' doesn't take text/html files.", fields: [] })
    expect(await screen.findByText("'screens' doesn't take text/html files.")).toBeInTheDocument()
    expect(shot.value()).toBe("screens/ab12/jam.png")

    await userEvent.click(screen.getByRole("button", { name: /remove/i }))
    expect(shot.value()).toBeNull()
  })

  it("previews an image with the panel's credentials", async () => {
    URL.createObjectURL = vi.fn(() => "blob:preview")
    URL.revokeObjectURL = vi.fn()
    const fetch = fakeFetch(({ url }) => (url.pathname === "/files/screens/ab12/jam.png" ? { body: "bytes" } : undefined))
    renderWidget(fieldOf(tickets, "screenshot"), "screens/ab12/jam.png", fetch)
    expect(await screen.findByRole("img", { name: "jam.png" })).toHaveAttribute("src", "blob:preview")
  })
})
