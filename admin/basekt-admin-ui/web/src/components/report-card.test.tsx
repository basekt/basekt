import { screen, within } from "@testing-library/react"
import { describe, expect, it } from "vitest"
import { fakeFetch, helpDeskSchema, librarySchema } from "@/test/fixtures"
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

  it("leave the home screen as it was when no resource has reports", async () => {
    const fetch = fakeFetch(({ url }) => (url.pathname === "/admin/api/schema" ? { body: librarySchema } : undefined))
    renderPanel("/admin/", fetch)

    expect(await screen.findByRole("heading", { name: "Test", level: 1 })).toBeInTheDocument()
    expect(screen.queryByRole("region", { name: "Reports" })).not.toBeInTheDocument()
  })
})
