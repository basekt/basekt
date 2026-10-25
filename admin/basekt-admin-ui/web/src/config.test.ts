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
    expect(config).toEqual({ path: "/back-office", apiPath: "/back-office/api", filesPath: "/uploads", brand: { name: "Acme", logo: "/logo.svg" }, defaultMode: "dark", login: null, provider: null, siteLink: null })
  })

  it("reads the host's login route, filling in what an older plugin leaves out", () => {
    const login = { path: "/auth/login", usernameField: "email", usernameLabel: "Email", passwordField: "password", tokenField: "token" }
    expect(readConfig(page(JSON.stringify({ path: "/admin", login }))).login).toEqual({ ...login, refreshPath: null, refreshTokenField: "refreshToken", expiresInField: "expiresIn" })
    const refreshing = { ...login, refreshPath: "/auth/refresh", refreshTokenField: "renewal", expiresInField: "ttl" }
    expect(readConfig(page(JSON.stringify({ path: "/admin", login: refreshing }))).login).toEqual(refreshing)
  })

  it("reads an identity provider", () => {
    const firebase = { type: "firebase", apiKey: "public-key", authDomain: "garden.firebaseapp.com", projectId: null, providers: ["password", "google.com"] }
    expect(readConfig(page(JSON.stringify({ provider: firebase }))).provider).toEqual(firebase)
    const oidc = { type: "oidc", issuer: "https://id.school.test", clientId: "panel", scopes: ["openid", "offline_access"], label: "school account" }
    expect(readConfig(page(JSON.stringify({ provider: oidc }))).provider).toEqual(oidc)
    expect(readConfig(page("{}")).provider).toBeNull()
  })

  it("falls back to the defaults without one", () => {
    expect(readConfig(page(null)).apiPath).toBe("/admin/api")
  })
})
