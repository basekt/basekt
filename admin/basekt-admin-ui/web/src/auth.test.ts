import { describe, expect, it, vi } from "vitest"
import { jwtExpiry, pastedSession, RefreshUnavailable, SessionKeeper, TokenStore, type Session } from "@/auth"
import { ManualClock, memoryStorage } from "@/test/session"

function jwt(claims: object): string {
  const part = (value: object) => btoa(JSON.stringify(value)).replace(/=+$/, "").replace(/\+/g, "-").replace(/\//g, "_")
  return `${part({ alg: "none" })}.${part(claims)}.sig`
}

const session = (token: string, expiresAt: number | null, refreshToken: string | null = "r"): Session => ({ token, source: "login", refreshToken, expiresAt })

describe("TokenStore", () => {
  it("keeps a remembered session in the tab's storage, and a refreshed one where the first was", () => {
    const storage = memoryStorage()
    const store = new TokenStore(storage)
    store.set(session("t1", 1000), true)
    expect(new TokenStore(storage).session()).toEqual(session("t1", 1000))
    store.update(session("t2", 2000))
    expect(new TokenStore(storage).get()).toBe("t2")

    store.set(session("t3", 3000), false)
    expect(new TokenStore(storage).session()).toBeNull()
    store.update(session("t4", 4000))
    expect(store.get()).toBe("t4")
    expect(new TokenStore(storage).session()).toBeNull()

    store.clear()
    expect(store.get()).toBeNull()
  })

  it("reads a pasted JWT's expiry, and nothing from other tokens", () => {
    expect(jwtExpiry(jwt({ exp: 2_000_000_000 }))).toBe(2_000_000_000_000)
    expect(jwtExpiry("opaque-token")).toBeNull()
    expect(jwtExpiry("a.not-json.c")).toBeNull()
    expect(pastedSession("opaque-token")).toEqual({ token: "opaque-token", source: "token", refreshToken: null, expiresAt: null })
  })
})

describe("SessionKeeper", () => {
  function keeper(refresh: ((s: Session) => Promise<Session>) | null, start: Session) {
    const clock = new ManualClock()
    const store = new TokenStore(null)
    store.set(start, false)
    const changed = vi.fn()
    const ended = vi.fn()
    const keeper = new SessionKeeper(store, () => refresh, changed, ended, clock)
    keeper.watch()
    return { clock, store, changed, ended, keeper }
  }

  it("refreshes a minute before expiry, and again before the next one", async () => {
    const refresh = vi.fn(async (s: Session) => session(s.token + "+", s.expiresAt! + 600_000, s.refreshToken))
    const { clock, store, changed } = keeper(refresh, session("t", 600_000))

    await clock.advance(539_000)
    expect(refresh).not.toHaveBeenCalled()
    await clock.advance(1_000)
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(store.get()).toBe("t+")
    expect(changed).toHaveBeenCalledWith(session("t+", 1_200_000))

    await clock.advance(600_000)
    expect(refresh).toHaveBeenCalledTimes(2)
    expect(store.get()).toBe("t++")
  })

  it("refreshes a short-lived token halfway through its life", async () => {
    const refresh = vi.fn(async () => session("next", 1_000_000))
    const { clock } = keeper(refresh, session("t", 30_000))
    await clock.advance(14_999)
    expect(refresh).not.toHaveBeenCalled()
    await clock.advance(1)
    expect(refresh).toHaveBeenCalledTimes(1)
  })

  it("ends the session when a refresh is refused", async () => {
    const refresh = vi.fn(async () => {
      throw new Error("refused")
    })
    const { clock, store, ended, changed } = keeper(refresh, session("t", 120_000))
    await clock.advance(60_000)
    expect(store.session()).toBeNull()
    expect(ended).toHaveBeenCalledWith("Your session ended. Sign in again.", session("t", 120_000))
    expect(changed).not.toHaveBeenCalled()
    expect(clock.pending()).toBe(0)
  })

  it("tries again while the network is down and the token still works, then ends it", async () => {
    const refresh = vi.fn(async (): Promise<Session> => {
      throw new RefreshUnavailable("offline")
    })
    const { clock, store, ended } = keeper(refresh, session("t", 120_000))
    await clock.advance(60_000)
    expect(store.get()).toBe("t")
    await clock.advance(50_000)
    expect(refresh.mock.calls.length).toBeGreaterThan(5)
    expect(ended).not.toHaveBeenCalled()
    await clock.advance(10_000)
    expect(store.session()).toBeNull()
    expect(ended).toHaveBeenCalledTimes(1)
  })

  it("leaves a session nobody can refresh alone, and stops when told", async () => {
    const quiet = keeper(null, session("pasted", 60_000, null))
    expect(quiet.clock.pending()).toBe(0)

    const refresh = vi.fn(async () => session("next", 1_000_000))
    const stopped = keeper(refresh, session("t", 120_000))
    stopped.keeper.stop()
    await stopped.clock.advance(200_000)
    expect(refresh).not.toHaveBeenCalled()
  })
})
