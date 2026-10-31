import { describe, expect, it } from "vitest"
import { href, parseRoute } from "@/router"

describe("routes", () => {
  it("parses the panel's URLs under its path", () => {
    expect(parseRoute("/admin", "/admin")).toEqual({ screen: "home" })
    expect(parseRoute("/admin", "/admin/")).toEqual({ screen: "home" })
    expect(parseRoute("/admin", "/admin/r/books", "?sort=-fee")).toMatchObject({ screen: "list", resource: "books" })
    expect(parseRoute("/admin", "/admin/r/books/new")).toEqual({ screen: "create", resource: "books" })
    expect(parseRoute("/back-office", "/back-office/r/books/a%2Fb")).toEqual({ screen: "detail", resource: "books", id: "a/b" })
    expect(parseRoute("/admin", "/admin/p/status")).toMatchObject({ screen: "page", page: "status" })
    const customPages = [{ id: "live", label: "Live", path: "/live", render: () => null }]
    expect(parseRoute("/admin", "/admin/live", "?window=1h", customPages)).toMatchObject({ screen: "page", page: "live" })
    expect(href("/admin", { screen: "page", page: "live", search: new URLSearchParams() }, customPages)).toBe("/admin/live")
  })

  it("writes URLs that parse back", () => {
    for (const route of [
      { screen: "home" } as const,
      { screen: "create", resource: "books" } as const,
      { screen: "detail", resource: "books", id: "a/b c" } as const,
    ]) {
      expect(parseRoute("/admin", href("/admin", route))).toEqual(route)
    }
    expect(href("/admin", { screen: "list", resource: "books", search: new URLSearchParams({ "fee[gte]": "1" }) })).toBe("/admin/r/books?fee%5Bgte%5D=1")
  })
})
