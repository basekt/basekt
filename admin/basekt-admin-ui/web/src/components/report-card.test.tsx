import { screen, waitFor, within } from "@testing-library/react"
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
    await user.click(screen.getByRole("button", { name: "Last 30 days" }))
    expect(card).toHaveTextContent("Sum of views, last 30 days")
    expect(screen.getByRole("button", { name: "Last 30 days" })).toHaveAttribute("aria-pressed", "true")
    // Only the report with a range takes the window; the pie of states counts every ticket.
    await waitFor(() => expect(asked.filter((q) => "openedAt[gte]" in q).length).toBe(2))
    expect(asked.filter((q) => q.groupBy === "state").every((q) => !("openedAt[gte]" in q))).toBe(true)
    expect(localStorage.getItem("basekt.reportRange")).toBe("30day")

    await user.click(screen.getByRole("button", { name: "Default" }))
    expect(card).toHaveTextContent("Sum of views, last 7 days")
    expect(localStorage.getItem("basekt.reportRange")).toBeNull()
  })

  it("leave the home screen as it was when no resource has reports", async () => {
    const fetch = fakeFetch(({ url }) => (url.pathname === "/admin/api/schema" ? { body: librarySchema } : undefined))
    renderPanel("/admin/", fetch)

    expect(await screen.findByRole("heading", { name: "Test", level: 1 })).toBeInTheDocument()
    expect(screen.queryByRole("region", { name: "Reports" })).not.toBeInTheDocument()
    expect(screen.queryByRole("group", { name: "Report range" })).not.toBeInTheDocument()
  })
})
