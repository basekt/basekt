import { describe, expect, it } from "vitest"
import { readConfig } from "@/config"

function page(json: string | null): Document {
  const doc = document.implementation.createHTMLDocument()
  if (json != null) {
    const script = doc.createElement("script")
    script.id = "basekt-admin-config"
    script.type = "application/json"
    script.textContent = json
    doc.head.append(script)
  }
  return doc
}

describe("readConfig", () => {
  it("reads the shell page's config", () => {
    const config = readConfig(page('{"path":"/back-office/","apiPath":"/back-office/api","filesPath":"/uploads","brand":{"name":"Acme","logo":"/logo.svg"},"defaultMode":"dark"}'))
    expect(config).toEqual({ path: "/back-office", apiPath: "/back-office/api", filesPath: "/uploads", brand: { name: "Acme", logo: "/logo.svg" }, defaultMode: "dark" })
  })

  it("falls back to the defaults without one", () => {
    expect(readConfig(page(null)).apiPath).toBe("/admin/api")
  })
})
