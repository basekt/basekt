import { fireEvent, screen, waitFor, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, expect, it } from "vitest"
import { fakeFetch, helpDeskSchema, librarySchema, tickets } from "@/test/fixtures"
import { renderPanel } from "@/test/render"

describe("the reports on the home screen", () => {
  it("draw each report the caller may see from its aggregate", async () => {
    const asked: Record<string, string>[] = []
    const fetch = fakeFetch(({ url }) => {
      if (url.pathname === "/admin/api/schema") return { body: helpDeskSchema }
      if (url.pathname !== "/admin/api/resources/tickets/aggregate") return undefined
      asked.push(Object.fromEntries(url.searchParams))
      return url.searchParams.has("groupBy")
        ? { body: { groups: [{ key: { state: "open" }, values: { count: 3 } }, { key: { state: "closed" }, values: { count: 1 } }], hasMore: false } }
        : { body: { groups: [{ key: {}, values: { sum_views: 1234 } }], hasMore: false } }
    })
    renderPanel("/admin/", fetch)

    const reports = await screen.findByRole("region", { name: "Reports" })
    const views = within(reports).getByRole("figure", { name: "Total views" })
    expect(await within(views).findByText((1234).toLocaleString())).toBeInTheDocument()
    expect(within(views).getByText("Sum of views")).toBeInTheDocument()
    expect(within(reports).getByRole("figure", { name: "Tickets by state" })).toHaveTextContent("Count by state")
    expect(asked).toEqual(expect.arrayContaining([{ count: "*", groupBy: "state", sort: "-count", limit: "12" }, { sum: "views", limit: "12" }]))
  })

  it("let the viewer pick the window of every report with a range, and keep the choice", async () => {
    const user = userEvent.setup()
    const lastWeek = { ...tickets.reports[1], name: "viewsLastWeek", range: { field: "openedAt", last: 7, unit: "day" as const } }
    const schema = { ...helpDeskSchema, resources: [{ ...tickets, reports: [tickets.reports[0], lastWeek] }] }
    const asked: Record<string, string>[] = []
    const fetch = fakeFetch(({ url }) => {
      if (url.pathname === "/admin/api/schema") return { body: schema }
      if (url.pathname !== "/admin/api/resources/tickets/aggregate") return undefined
      asked.push(Object.fromEntries(url.searchParams))
      return { body: { groups: [{ key: {}, values: { sum_views: 7, count: 1 } }], hasMore: false } }
    })
    renderPanel("/admin/", fetch)

    const reports = await screen.findByRole("region", { name: "Reports" })
    const card = within(reports).getByRole("figure", { name: "Views last week" })
    expect(card).toHaveTextContent("Sum of views, last 7 days")
    await user.click(screen.getByRole("button", { name: "Report range" }))
    const presets = await screen.findByRole("group", { name: "Presets" })
    await user.click(within(presets).getByRole("button", { name: "Last 30 days" }))
    expect(card).toHaveTextContent("Sum of views, last 30 days")
    expect(within(presets).getByRole("button", { name: "Last 30 days" })).toHaveAttribute("aria-pressed", "true")
    // Only the report with a range takes the window; the pie of states counts every ticket.
    await waitFor(() => expect(asked.filter((q) => "openedAt[gte]" in q).length).toBe(2))
    expect(asked.filter((q) => q.groupBy === "state").every((q) => !("openedAt[gte]" in q))).toBe(true)
    expect(JSON.parse(localStorage.getItem("basekt.reportRange")!)).toEqual({ last: 30, unit: "day" })
    // The picker shows the window's dates, ending today.
    expect((screen.getByLabelText("Last day") as HTMLInputElement).value).toBe(new Date().toISOString().slice(0, 10))

    await user.click(within(presets).getByRole("button", { name: "Last month" }))
    expect(card).toHaveTextContent("Sum of views, last month")
    await user.click(within(presets).getByRole("button", { name: "Default" }))
    expect(card).toHaveTextContent("Sum of views, last 7 days")
    expect(localStorage.getItem("basekt.reportRange")).toBeNull()
  }, 15_000)

  it("let one card pick its own dates and compare with an earlier window", async () => {
    const user = userEvent.setup()
    const weekViews = { ...tickets.reports[1], name: "weekViews", range: { field: "openedAt", last: 7, unit: "day" as const } }
    const schema = { ...helpDeskSchema, resources: [{ ...tickets, reports: [weekViews, { ...weekViews, name: "otherViews" }] }] }
    const asked: Record<string, string>[] = []
    const fetch = fakeFetch(({ url }) => {
      if (url.pathname === "/admin/api/schema") return { body: schema }
      if (url.pathname !== "/admin/api/resources/tickets/aggregate") return undefined
      asked.push(Object.fromEntries(url.searchParams))
      return { body: { groups: [{ key: {}, values: { sum_views: 10 } }], hasMore: false } }
    })
    renderPanel("/admin/", fetch)

    const card = await screen.findByRole("figure", { name: "Week views" })
    await user.click(within(card).getByRole("button", { name: "Range of Week views" }))
    const compareTo = await screen.findByRole("switch", { name: "Compare to" })
    await user.click(compareTo)
    expect(await within(card).findByText(/vs same period last year \(/)).toBeInTheDocument()
    await user.click(within(screen.getByRole("group", { name: "Compare with" })).getByRole("button", { name: "previous period" }))
    expect(await within(card).findByText(/vs previous period \(/)).toBeInTheDocument()
    expect(card).toHaveTextContent("Sum of views, last 7 days vs previous period")
    // The other card keeps the report's defaults.
    expect(screen.getByRole("figure", { name: "Other views" })).toHaveTextContent("Sum of views, last 7 days")
    expect(screen.getByRole("figure", { name: "Other views" })).not.toHaveTextContent("vs")
    const windows = asked.filter((q) => q["openedAt[lt]"]).map((q) => [q["openedAt[gte]"], q["openedAt[lt]"]])
    expect(windows.some(([, lt]) => windows.some(([gte]) => gte === lt))).toBe(true)
    expect(JSON.parse(localStorage.getItem("basekt.report.tickets.weekViews")!)).toEqual({ compare: "previousPeriod" })

    // Two clicks on the calendar choose a range; the calendar redraws its days on each, so each is looked up anew.
    const picker = screen.getByRole("dialog")
    const day = (n: number) =>
      within(picker).getAllByRole("gridcell").filter((cell) => !cell.hasAttribute("data-outside")).map((cell) => within(cell).getByRole("button"))[n]
    await user.click(day(2))
    await user.click(day(4))
    const saved = JSON.parse(localStorage.getItem("basekt.report.tickets.weekViews")!)
    expect(saved.range.from < saved.range.to).toBe(true)
    expect(card).toHaveTextContent(" – ")
    expect((within(picker).getByLabelText("First day") as HTMLInputElement).value).toBe(saved.range.from)

    // The date fields change the range too.
    fireEvent.change(within(picker).getByLabelText("First day"), { target: { value: "2026-01-05" } })
    expect(JSON.parse(localStorage.getItem("basekt.report.tickets.weekViews")!).range.from).toBe("2026-01-05")

    await user.click(within(within(picker).getByRole("group", { name: "Presets" })).getByRole("button", { name: "Default" }))
    expect(card).toHaveTextContent("Sum of views, last 7 days vs previous period")
    expect(JSON.parse(localStorage.getItem("basekt.report.tickets.weekViews")!)).toEqual({ compare: "previousPeriod" })
  }, 15_000) // A popover, a switch and a calendar: slow runners need more than the default 5s.

  it("leave the home screen as it was when no resource has reports", async () => {
    const fetch = fakeFetch(({ url }) => (url.pathname === "/admin/api/schema" ? { body: librarySchema } : undefined))
    renderPanel("/admin/", fetch)

    expect(await screen.findByRole("heading", { name: "Test", level: 1 })).toBeInTheDocument()
    expect(screen.queryByRole("region", { name: "Reports" })).not.toBeInTheDocument()
    expect(screen.queryByRole("group", { name: "Report range" })).not.toBeInTheDocument()
  })
})
