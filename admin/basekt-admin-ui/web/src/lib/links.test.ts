import { describe, expect, it } from "vitest"
import { fillLink, isSafeHref, recordLinks } from "@/lib/links"

describe("fillLink", () => {
  it("writes the id and fields into the address, percent-encoded", () => {
    expect(fillLink("/lessons/{id}", { id: "quadratics" })).toBe("/lessons/quadratics")
    expect(fillLink("/preview?slug={slug}&n={n}", { id: "x", slug: "a b/c?d", n: 3 })).toBe("/preview?slug=a%20b%2Fc%3Fd&n=3")
    expect(fillLink("https://example.test/p/{id}", { id: "a&b" })).toBe("https://example.test/p/a%26b")
  })

  it("can't be turned into another address by a value", () => {
    expect(fillLink("/lessons/{id}", { id: "../../admin" })).toBe("/lessons/..%2F..%2Fadmin")
    expect(fillLink("/{id}", { id: "/evil.test" })).toBe("/%2Fevil.test")
  })

  it("gives nothing when a value is missing, empty or not a plain value", () => {
    expect(fillLink("/p/{slug}", { id: "x", slug: null })).toBeNull()
    expect(fillLink("/p/{slug}", { id: "x" })).toBeNull()
    expect(fillLink("/p/{slug}", { id: "x", slug: "" })).toBeNull()
    expect(fillLink("/p/{tags}", { id: "x", tags: ["a"] })).toBeNull()
    expect(fillLink("/about", {})).toBe("/about")
  })
})

describe("isSafeHref", () => {
  it("takes paths and http addresses only", () => {
    for (const ok of ["/a", "/a/b?c=d", "https://example.test/", "http://localhost:8080/x"]) expect(isSafeHref(ok), ok).toBe(true)
    for (const bad of ["//evil.test", "javascript:alert(1)", "data:text/html,x", "mailto:a@b.test", "lessons/1", ""]) expect(isSafeHref(bad), bad).toBe(false)
  })
})

describe("recordLinks", () => {
  const links = [
    { label: "View on site", url: "/lessons/{id}" },
    { label: "Preview", url: "/preview?slug={slug}" },
  ]

  it("keeps the links the record has every value for", () => {
    expect(recordLinks(links, { id: "q", slug: "squares" })).toEqual([
      { label: "View on site", href: "/lessons/q" },
      { label: "Preview", href: "/preview?slug=squares" },
    ])
    expect(recordLinks(links, { id: "q", slug: null })).toEqual([{ label: "View on site", href: "/lessons/q" }])
  })

  it("drops a link that would not be followed", () => {
    expect(recordLinks([{ label: "Bad", url: "{id}" }], { id: "javascript:alert(1)" })).toEqual([])
  })
})
