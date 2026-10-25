import { render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, expect, it, vi } from "vitest"
import { App } from "@/App"
import type { ProviderClient } from "@/api/providers"
import { TokenStore, type Session } from "@/auth"
import type { LoginRoute, PanelConfig } from "@/config"
import { apiError, config, fakeFetch, helpDeskSchema, librarySchema, page } from "@/test/fixtures"
import { ManualClock, memoryStorage } from "@/test/session"

// Two made-up hosts: a library whose login route answers `{token, refreshToken, expiresIn}`, and a help desk that
// signs its staff in through an identity provider.

const login: LoginRoute = {
  path: "/auth/login",
  usernameField: "email",
  usernameLabel: "Email",
  passwordField: "password",
  tokenField: "token",
  refreshPath: "/auth/refresh",
  refreshTokenField: "refreshToken",
  expiresInField: "expiresIn",
}
const withRefresh: PanelConfig = { ...config, login }

/** A library host: `r1` → `t1` lives five minutes, and each refresh token is good for one refresh. */
function libraryHost(refuseRefresh = false) {
  let issued = 1
  const valid = new Set(["t1"])
  const refreshTokens = new Set(["r1"])
  return fakeFetch(({ method, url, headers, body }) => {
    if (url.pathname === "/auth/login" && method === "POST") return { body: { token: "t1", refreshToken: "r1", expiresIn: 300 } }
    if (url.pathname === "/auth/refresh" && method === "POST") {
      const given = (body as { refreshToken?: string }).refreshToken ?? ""
      if (refuseRefresh || !refreshTokens.delete(given)) return { status: 401, body: apiError("unauthorized", "That refresh token is spent.") }
      issued++
      valid.add(`t${issued}`)
      refreshTokens.add(`r${issued}`)
      return { body: { token: `t${issued}`, refreshToken: `r${issued}`, expiresIn: 300 } }
    }
    const bearer = headers.Authorization?.replace("Bearer ", "")
    if (!bearer || !valid.has(bearer)) return { status: 401, body: apiError("unauthorized", "Sign in.") }
    if (url.pathname === "/admin/api/schema") return { body: librarySchema }
    if (url.pathname === "/admin/api/resources/books/records") return { body: page([]) }
  })
}

async function signInWithPassword() {
  const user = userEvent.setup()
  await user.type(await screen.findByLabelText("Email"), "bea@library.test")
  await user.type(screen.getByLabelText("Password"), "right")
  await user.click(screen.getByRole("button", { name: "Sign in" }))
  expect(await screen.findByText("No records yet.")).toBeInTheDocument()
  return user
}

describe("a session from the login route", () => {
  it("is refreshed before it expires, sending the refresh token only to the refresh route", async () => {
    const clock = new ManualClock()
    const fetch = libraryHost()
    const storage = memoryStorage()
    history.replaceState(null, "", "/admin/r/books")
    render(<App config={withRefresh} tokens={new TokenStore(storage)} fetchImpl={fetch} clock={clock} />)
    await signInWithPassword()
    expect(JSON.parse(storage.getItem("basekt-admin-ui:session")!)).toMatchObject({ token: "t1", refreshToken: "r1", expiresAt: 300_000 })

    await clock.advance(239_000)
    expect(fetch.calls.some((call) => call.url.pathname === "/auth/refresh")).toBe(false)
    await clock.advance(1_000)
    await clock.advance(300_000)
    const refreshes = fetch.calls.filter((call) => call.url.pathname === "/auth/refresh")
    expect(refreshes.map((call) => call.body)).toEqual([{ refreshToken: "r1" }, { refreshToken: "r2" }])
    expect(JSON.parse(storage.getItem("basekt-admin-ui:session")!)).toMatchObject({ token: "t3", refreshToken: "r3" })

    // The panel's own calls carry the newest token, and no refresh token goes anywhere but the refresh route.
    await userEvent.setup().click(screen.getByRole("link", { name: /Authors/ }))
    expect(fetch.calls.at(-1)!.headers.Authorization).toBe("Bearer t3")
    const leaks = fetch.calls.filter((call) => /\br\d\b/.test(JSON.stringify(call.headers) + JSON.stringify(call.body ?? "") + call.url.search))
    expect(leaks.every((call) => call.url.pathname === "/auth/refresh")).toBe(true)
  })

  it("signs out cleanly when the refresh fails", async () => {
    const clock = new ManualClock()
    const fetch = libraryHost(true)
    const tokens = new TokenStore(memoryStorage())
    history.replaceState(null, "", "/admin/r/books")
    render(<App config={withRefresh} tokens={tokens} fetchImpl={fetch} clock={clock} />)
    await signInWithPassword()

    await clock.advance(240_000)
    expect(await screen.findByText("Your session ended. Sign in again.")).toBeInTheDocument()
    expect(screen.getByLabelText("Email")).toBeInTheDocument()
    expect(tokens.session()).toBeNull()
    expect(clock.pending()).toBe(0)
  })

  it("stops refreshing once the viewer signs out", async () => {
    const clock = new ManualClock()
    const fetch = libraryHost()
    const tokens = new TokenStore(memoryStorage())
    history.replaceState(null, "", "/admin/r/books")
    render(<App config={withRefresh} tokens={tokens} fetchImpl={fetch} clock={clock} />)
    const user = await signInWithPassword()

    await user.click(screen.getByRole("button", { name: /sign out/i }))
    expect(await screen.findByText("The admin API needs you to sign in.")).toBeInTheDocument()
    expect(tokens.session()).toBeNull()
    await clock.advance(600_000)
    expect(fetch.calls.some((call) => call.url.pathname === "/auth/refresh")).toBe(false)
  })

  it("keeps no refresh token when the panel has no refresh route", async () => {
    const fetch = libraryHost()
    const tokens = new TokenStore(memoryStorage())
    history.replaceState(null, "", "/admin/r/books")
    render(<App config={{ ...config, login: { ...login, refreshPath: null } }} tokens={tokens} fetchImpl={fetch} clock={new ManualClock()} />)
    await signInWithPassword()
    expect(tokens.session()).toMatchObject({ token: "t1", refreshToken: null })
  })

  it("renews an expired session from before a reload before loading anything", async () => {
    const clock = new ManualClock()
    const fetch = libraryHost()
    const storage = memoryStorage()
    const tokens = new TokenStore(storage)
    tokens.set({ token: "t0", source: "login", refreshToken: "r1", expiresAt: -1 }, true)
    history.replaceState(null, "", "/admin/r/books")
    render(<App config={withRefresh} tokens={new TokenStore(storage)} fetchImpl={fetch} clock={clock} />)
    await clock.advance(0)
    expect(await screen.findByText("No records yet.")).toBeInTheDocument()
    expect(fetch.calls[0].url.pathname).toBe("/auth/refresh")
    expect(fetch.calls.filter((call) => call.headers.Authorization === "Bearer t0")).toEqual([])
  })
})

describe("a session from an identity provider", () => {
  const providerConfig: PanelConfig = { ...config, provider: { type: "oidc", issuer: "https://id.desk.test", clientId: "panel", scopes: ["openid", "offline_access"], label: "desk account" } }

  function deskProvider() {
    let n = 0
    const idToken = (): Session => ({ token: `id-${++n}`, source: "provider", refreshToken: null, expiresAt: 3_600_000 * n })
    const client: ProviderClient = {
      methods: [{ id: "oidc", kind: "button", label: "Continue with desk account" }],
      signIn: vi.fn(async () => idToken()),
      complete: vi.fn(async () => null),
      refresh: vi.fn(async () => idToken()),
      signOut: vi.fn(async () => {}),
    }
    const fetch = fakeFetch(({ url, headers }) => {
      if (!headers.Authorization?.startsWith("Bearer id-")) return { status: 401, body: apiError("unauthorized", "Sign in.") }
      if (url.pathname === "/admin/api/schema") return { body: helpDeskSchema }
      if (url.pathname === "/admin/api/resources/tickets/records") return { body: page([]) }
    })
    return { client, fetch }
  }

  it("sends the provider's ID token, refreshes it with the provider, and signs out of it", async () => {
    const user = userEvent.setup()
    const clock = new ManualClock()
    const { client, fetch } = deskProvider()
    const tokens = new TokenStore(memoryStorage())
    history.replaceState(null, "", "/admin/r/tickets")
    render(<App config={providerConfig} tokens={tokens} fetchImpl={fetch} provider={client} clock={clock} />)

    await user.click(await screen.findByRole("button", { name: "Continue with desk account" }))
    expect(await screen.findByText("No records yet.")).toBeInTheDocument()
    expect(fetch.calls.at(-1)!.headers.Authorization).toBe("Bearer id-1")

    await clock.advance(3_540_000)
    expect(client.refresh).toHaveBeenCalledTimes(1)
    expect(tokens.get()).toBe("id-2")

    await user.click(screen.getByRole("button", { name: /sign out/i }))
    expect(await screen.findByText("The admin API needs you to sign in.")).toBeInTheDocument()
    expect(client.signOut).toHaveBeenCalledTimes(1)
  })

  it("signs out of the provider too when its refresh fails", async () => {
    const user = userEvent.setup()
    const clock = new ManualClock()
    const { client, fetch } = deskProvider()
    client.refresh = vi.fn(async () => {
      throw new Error("The identity provider gave no refresh token.")
    })
    history.replaceState(null, "", "/admin/r/tickets")
    render(<App config={providerConfig} tokens={new TokenStore(null)} fetchImpl={fetch} provider={client} clock={clock} />)
    await user.click(await screen.findByRole("button", { name: "Continue with desk account" }))
    expect(await screen.findByText("No records yet.")).toBeInTheDocument()

    await clock.advance(3_600_000)
    expect(await screen.findByText("Your session ended. Sign in again.")).toBeInTheDocument()
    expect(client.signOut).toHaveBeenCalledTimes(1)
  })

  it("keeps the pasted token one click away, and never refreshes it with the provider", async () => {
    const user = userEvent.setup()
    const clock = new ManualClock()
    const { client, fetch } = deskProvider()
    history.replaceState(null, "", "/admin/r/tickets")
    render(<App config={providerConfig} tokens={new TokenStore(null)} fetchImpl={fetch} provider={client} clock={clock} />)
    await user.click(await screen.findByRole("button", { name: "Use an access token" }))
    await user.type(screen.getByLabelText("Access token"), "id-pasted")
    await user.click(screen.getByRole("button", { name: "Sign in" }))
    expect(await screen.findByText("No records yet.")).toBeInTheDocument()
    await clock.advance(10 * 3_600_000)
    expect(client.refresh).not.toHaveBeenCalled()
  })
})
