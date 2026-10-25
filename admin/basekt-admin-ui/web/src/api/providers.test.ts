import { beforeEach, describe, expect, it, vi } from "vitest"
import { providerClient, SignInCancelled, signInMethods } from "@/api/providers"
import { RefreshUnavailable, type Session } from "@/auth"
import type { FirebaseProvider, OidcProvider } from "@/config"

const oidc = vi.hoisted(() => {
  const managers: { settings: Record<string, unknown>; user: Record<string, unknown> | null }[] = []
  return { managers, callbackUser: null as Record<string, unknown> | null, silent: null as (() => Promise<unknown>) | null }
})

vi.mock("oidc-client-ts", () => {
  class WebStorageStateStore {
    constructor(readonly options: { store: unknown }) {}
  }
  class InMemoryWebStorage {}
  class UserManager {
    private readonly entry: (typeof oidc.managers)[number]
    constructor(settings: Record<string, unknown>) {
      this.entry = { settings, user: null }
      oidc.managers.push(this.entry)
    }
    signinRedirect = vi.fn(async () => {})
    signinRedirectCallback = vi.fn(async () => (this.entry.user = oidc.callbackUser))
    removeUser = vi.fn(async () => void (this.entry.user = null))
    storeUser = vi.fn(async (user: Record<string, unknown>) => void (this.entry.user = user))
    getUser = vi.fn(async () => this.entry.user)
    signinSilent = vi.fn(async () => oidc.silent!())
  }
  return { UserManager, WebStorageStateStore, InMemoryWebStorage }
})

const firebase = vi.hoisted(() => ({
  persistence: [] as unknown[],
  initial: null as unknown,
  user: null as { getIdTokenResult: (force: boolean) => Promise<{ token: string; expirationTime: string }> } | null,
  popupProvider: null as unknown,
  failWith: null as string | null,
}))

vi.mock("firebase/app", () => ({ getApps: () => [], initializeApp: (options: object, name: string) => ({ options, name }) }))
vi.mock("firebase/auth", () => {
  class Provider {
    constructor(readonly providerId: string) {}
  }
  const fail = () => {
    if (firebase.failWith) throw Object.assign(new Error(firebase.failWith), { code: firebase.failWith })
  }
  const auth = { get currentUser() { return firebase.user }, authStateReady: async () => {} }
  return {
    browserSessionPersistence: "session",
    inMemoryPersistence: "memory",
    browserPopupRedirectResolver: "popup",
    initializeAuth: (_app: unknown, deps: { persistence: unknown[] }) => {
      firebase.initial = deps.persistence
      return auth
    },
    setPersistence: async (_auth: unknown, persistence: unknown) => void firebase.persistence.push(persistence),
    signInWithEmailAndPassword: async () => (fail(), { user: firebase.user }),
    signInWithPopup: async (_auth: unknown, provider: unknown) => {
      firebase.popupProvider = provider
      fail()
      return { user: firebase.user }
    },
    signOut: async () => void (firebase.user = null),
    GoogleAuthProvider: class extends Provider { constructor() { super("google.com") } },
    GithubAuthProvider: class extends Provider { constructor() { super("github.com") } },
    FacebookAuthProvider: class extends Provider { constructor() { super("facebook.com") } },
    TwitterAuthProvider: class extends Provider { constructor() { super("twitter.com") } },
    SAMLAuthProvider: Provider,
    OAuthProvider: Provider,
  }
})

function idToken(exp: number): string {
  const part = (value: object) => btoa(JSON.stringify(value)).replace(/=+$/, "")
  return `${part({ alg: "RS256" })}.${part({ exp })}.sig`
}

const school: OidcProvider = { type: "oidc", issuer: "https://id.school.test", clientId: "panel", scopes: ["openid", "offline_access"], label: "school account" }
const garden: FirebaseProvider = { type: "firebase", apiKey: "public-key", authDomain: "garden.firebaseapp.com", projectId: null, providers: ["password", "google.com", "oidc.allotment"] }

describe("sign-in methods", () => {
  it("names each way in without loading an SDK", () => {
    expect(signInMethods(school)).toEqual([{ id: "oidc", kind: "button", label: "Continue with school account" }])
    expect(signInMethods(garden)).toEqual([
      { id: "password", kind: "password", label: "Email" },
      { id: "google.com", kind: "button", label: "Continue with Google" },
      { id: "oidc.allotment", kind: "button", label: "Continue with allotment" },
    ])
  })
})

describe("an OIDC issuer", () => {
  beforeEach(() => {
    oidc.managers.length = 0
    oidc.callbackUser = null
  })

  it("redirects with PKCE to the issuer, and comes back to where the viewer was", async () => {
    history.replaceState(null, "", "/staff/r/classes?term=spring")
    const client = providerClient(school, "/staff")
    expect(await client.signIn("oidc", true)).toBeNull()
    const settings = oidc.managers[0].settings
    expect(settings).toMatchObject({
      authority: "https://id.school.test",
      client_id: "panel",
      redirect_uri: `${location.origin}/staff/signed-in`,
      response_type: "code",
      scope: "openid offline_access",
      automaticSilentRenew: false,
    })
    expect((settings.stateStore as { options: { store: unknown } }).options.store).toBe(sessionStorage)

    history.replaceState(null, "", "/staff/signed-in?code=c&state=s")
    oidc.callbackUser = { id_token: idToken(2_000_000_000), refresh_token: "issuer-refresh", state: { returnTo: "/staff/r/classes?term=spring", remember: true } }
    const done = await providerClient(school, "/staff").complete()
    expect(done).toEqual({
      session: { token: idToken(2_000_000_000), source: "provider", refreshToken: null, expiresAt: 2_000_000_000_000 },
      remember: true,
      returnTo: "/staff/r/classes?term=spring",
    })
  })

  it("keeps a session the viewer didn't ask to keep in memory, and never returns outside the panel", async () => {
    history.replaceState(null, "", "/staff/signed-in?code=c&state=s")
    oidc.callbackUser = { id_token: idToken(2_000_000_000), state: { returnTo: "https://elsewhere.test/", remember: false } }
    const done = await providerClient(school, "/staff").complete()
    expect(done?.returnTo).toBe("/staff/")
    expect(done?.remember).toBe(false)
    expect(oidc.managers[0].user).toBeNull()
    expect(oidc.managers[1].user).toEqual(oidc.callbackUser)
    expect((oidc.managers[1].settings.userStore as { options: { store: unknown } }).options.store).not.toBe(sessionStorage)
  })

  it("does nothing on any other page", async () => {
    history.replaceState(null, "", "/staff/r/classes")
    expect(await providerClient(school, "/staff").complete()).toBeNull()
  })

  it("refreshes with the issuer's refresh token, and fails without one", async () => {
    history.replaceState(null, "", "/staff/signed-in")
    oidc.callbackUser = { id_token: idToken(1_900_000_000), refresh_token: "issuer-refresh", state: { remember: true } }
    const client = providerClient(school, "/staff")
    const done = await client.complete()
    oidc.silent = async () => ({ id_token: idToken(2_100_000_000) })
    expect((await client.refresh(done!.session)).token).toBe(idToken(2_100_000_000))

    oidc.silent = async () => {
      throw new TypeError("Failed to fetch")
    }
    await expect(client.refresh(done!.session)).rejects.toBeInstanceOf(RefreshUnavailable)

    oidc.managers[0].user = { id_token: "x" }
    await expect(client.refresh(done!.session)).rejects.toThrow(/offline_access/)
  })
})

describe("Firebase Authentication", () => {
  beforeEach(() => {
    firebase.persistence.length = 0
    firebase.failWith = null
    let n = 0
    firebase.user = { getIdTokenResult: async () => ({ token: `fb-${++n}`, expirationTime: new Date(3_600_000 * n).toUTCString() }) }
  })

  it("signs in with a password or a popup, keeping the session in the tab at most", async () => {
    const client = providerClient(garden, "/admin")
    expect(await client.signIn("password", false, { username: "kim@garden.test", password: "pw" })).toEqual({ token: "fb-1", source: "provider", refreshToken: null, expiresAt: 3_600_000 })
    expect(await client.signIn("google.com", true)).toMatchObject({ token: "fb-2" })
    expect(firebase.initial).toEqual(["session", "memory"])
    expect(firebase.persistence).toEqual(["memory", "session"])
    expect(firebase.popupProvider).toMatchObject({ providerId: "google.com" })
    await client.signIn("oidc.allotment", true)
    expect(firebase.popupProvider).toMatchObject({ providerId: "oidc.allotment" })
  })

  it("says why a sign-in failed, and stays quiet when the viewer closed the popup", async () => {
    const client = providerClient(garden, "/admin")
    firebase.failWith = "auth/invalid-credential"
    await expect(client.signIn("password", true, { username: "kim@garden.test", password: "no" })).rejects.toThrow("That email or password was refused.")
    firebase.failWith = "auth/popup-closed-by-user"
    await expect(client.signIn("google.com", true)).rejects.toBeInstanceOf(SignInCancelled)
  })

  it("refreshes the ID token with Firebase, and fails once it has no user", async () => {
    const client = providerClient(garden, "/admin")
    const first = (await client.signIn("google.com", true)) as Session
    expect((await client.refresh(first)).token).toBe("fb-2")
    await client.signOut()
    await expect(client.refresh(first)).rejects.toThrow(/no session/)
  })
})
