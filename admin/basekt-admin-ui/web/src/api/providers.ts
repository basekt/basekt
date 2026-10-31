import { jwtExpiry, RefreshUnavailable, type Session } from "@/auth"
import type { FirebaseProvider, OidcProvider, SignInProvider, SupabaseProvider } from "@/config"

/** One way to sign in that a provider offers: a name and password form, or a button. */
export interface SignInMethod {
  id: string
  kind: "password" | "button"
  /** The button's text, or the form's name label. */
  label: string
}

/** A sign-in that came back to the panel through a redirect. */
export interface CompletedSignIn {
  session: Session
  remember: boolean
  /** The panel URL the viewer was on when they started signing in. */
  returnTo: string
}

/**
 * Signs in through an identity provider and keeps its ID token fresh. The provider's own SDK keeps its refresh token
 * and sends it only to the provider; the panel sees the ID token only.
 */
export interface ProviderClient {
  /** The ways in the sign-in screen offers. */
  readonly methods: SignInMethod[]
  /**
   * Runs [method]'s sign-in. Resolves to the session, or to null when the browser leaves for the provider's page and
   * comes back through [complete]. Fails with an Error whose message the sign-in screen shows; a sign-in the viewer
   * closed fails with [SignInCancelled].
   */
  signIn(method: string, remember: boolean, credentials?: { username: string; password: string }): Promise<Session | null>
  /** Finishes a sign-in when the page's URL is the provider's way back to the panel, or resolves to null. */
  complete(): Promise<CompletedSignIn | null>
  /** A fresh ID token for [session], or a failure: [RefreshUnavailable] when the provider can't be reached. */
  refresh(session: Session): Promise<Session>
  /** Forgets the provider's session in this tab. */
  signOut(): Promise<void>
}

/** The viewer closed the provider's window: nothing to report. */
export class SignInCancelled extends Error {}

/** The ways [provider] offers in, worked out without loading its SDK. */
export function signInMethods(provider: SignInProvider): SignInMethod[] {
  if (provider.type === "oidc") return [{ id: "oidc", kind: "button", label: `Continue with ${provider.label}` }]
  if (provider.type === "supabase") {
    return provider.providers.map((id) =>
      id === "password" ? { id, kind: "password", label: "Email" } : { id, kind: "button", label: `Continue with ${supabaseLabel(id)}` },
    )
  }
  return provider.providers.map((id) =>
    id === "password" ? { id, kind: "password", label: "Email" } : { id, kind: "button", label: `Continue with ${firebaseLabel(id)}` },
  )
}

/** The client for [provider], loading its SDK only when a sign-in, a callback or a refresh needs it. */
export function providerClient(provider: SignInProvider, panelPath: string, fetchImpl: typeof fetch = fetch): ProviderClient {
  if (provider.type === "firebase") return firebaseClient(provider)
  if (provider.type === "supabase") return supabaseClient(provider, panelPath, fetchImpl)
  return oidcClient(provider, panelPath)
}

/** Where an OIDC issuer sends the viewer back to, under the panel's path. */
export function oidcCallbackPath(panelPath: string): string {
  return `${panelPath}/signed-in`
}

const firebaseNames: Record<string, string> = {
  "google.com": "Google",
  "github.com": "GitHub",
  "microsoft.com": "Microsoft",
  "apple.com": "Apple",
  "facebook.com": "Facebook",
  "twitter.com": "X",
  "yahoo.com": "Yahoo",
}

function firebaseLabel(id: string): string {
  return firebaseNames[id] ?? id.replace(/^(oidc|saml)\./, "")
}

const supabaseNames: Record<string, string> = {
  google: "Google",
  github: "GitHub",
  discord: "Discord",
  microsoft: "Microsoft",
  azure: "Microsoft",
  apple: "Apple",
  gitlab: "GitLab",
  slack: "Slack",
  twitch: "Twitch",
  twitter: "X",
}

function supabaseLabel(id: string): string {
  return supabaseNames[id] ?? (id.charAt(0).toUpperCase() + id.slice(1))
}

function firebaseClient(provider: FirebaseProvider): ProviderClient {
  // One Firebase app per panel, named so it never clashes with one the host page may load.
  const load = once(async () => {
    const [{ initializeApp, getApps }, auth] = await Promise.all([import("firebase/app"), import("firebase/auth")])
    const app =
      getApps().find((a) => a.name === "basekt-admin-ui") ??
      initializeApp({ apiKey: provider.apiKey, authDomain: provider.authDomain, ...(provider.projectId ? { projectId: provider.projectId } : {}) }, "basekt-admin-ui")
    // The tab's sessionStorage at most, like a pasted token; never IndexedDB or localStorage.
    const instance = auth.initializeAuth(app, {
      persistence: [auth.browserSessionPersistence, auth.inMemoryPersistence],
      popupRedirectResolver: auth.browserPopupRedirectResolver,
    })
    return { sdk: auth, auth: instance }
  })

  const sessionOf = async (user: import("firebase/auth").User, force: boolean): Promise<Session> => {
    const result = await user.getIdTokenResult(force)
    const expiresAt = Date.parse(result.expirationTime)
    return { token: result.token, source: "provider", refreshToken: null, expiresAt: Number.isNaN(expiresAt) ? jwtExpiry(result.token) : expiresAt }
  }

  return {
    methods: signInMethods(provider),
    async signIn(method, remember, credentials) {
      const { sdk, auth } = await load()
      await sdk.setPersistence(auth, remember ? sdk.browserSessionPersistence : sdk.inMemoryPersistence)
      try {
        const result =
          method === "password"
            ? await sdk.signInWithEmailAndPassword(auth, credentials?.username ?? "", credentials?.password ?? "")
            : await sdk.signInWithPopup(auth, firebaseProvider(sdk, method))
        return await sessionOf(result.user, false)
      } catch (error) {
        throw firebaseError(error)
      }
    },
    complete: async () => null,
    async refresh() {
      let loaded
      try {
        loaded = await load()
        await loaded.auth.authStateReady()
      } catch {
        throw new RefreshUnavailable("Couldn't reach the identity provider.")
      }
      const user = loaded.auth.currentUser
      if (!user) throw new Error("The identity provider has no session in this tab.")
      try {
        return await sessionOf(user, true)
      } catch (error) {
        if (firebaseCode(error) === "auth/network-request-failed") throw new RefreshUnavailable("Couldn't reach the identity provider.")
        throw error
      }
    },
    async signOut() {
      const { sdk, auth } = await load()
      await sdk.signOut(auth)
    },
  }
}

function firebaseProvider(sdk: typeof import("firebase/auth"), id: string): import("firebase/auth").AuthProvider {
  switch (id) {
    case "google.com":
      return new sdk.GoogleAuthProvider()
    case "github.com":
      return new sdk.GithubAuthProvider()
    case "facebook.com":
      return new sdk.FacebookAuthProvider()
    case "twitter.com":
      return new sdk.TwitterAuthProvider()
    default:
      return id.startsWith("saml.") ? new sdk.SAMLAuthProvider(id) : new sdk.OAuthProvider(id)
  }
}

function firebaseCode(error: unknown): string | null {
  return typeof error === "object" && error !== null && typeof (error as { code?: unknown }).code === "string" ? (error as { code: string }).code : null
}

function firebaseError(error: unknown): Error {
  switch (firebaseCode(error)) {
    case "auth/popup-closed-by-user":
    case "auth/cancelled-popup-request":
      return new SignInCancelled("Sign-in was closed.")
    case "auth/invalid-credential":
    case "auth/invalid-email":
    case "auth/wrong-password":
    case "auth/user-not-found":
      return new Error("That email or password was refused.")
    case "auth/user-disabled":
      return new Error("That account is disabled.")
    case "auth/too-many-requests":
      return new Error("Too many attempts. Wait a little and try again.")
    case "auth/popup-blocked":
      return new Error("The browser blocked the sign-in window. Allow pop-ups for this page and try again.")
    case "auth/network-request-failed":
      return new Error("Couldn't reach the identity provider. Check the connection and try again.")
    default:
      return error instanceof Error ? error : new Error(String(error))
  }
}

interface OidcState {
  returnTo: string
  remember: boolean
}

function oidcClient(provider: OidcProvider, panelPath: string): ProviderClient {
  const callbackPath = oidcCallbackPath(panelPath)
  let remembered = true
  const managers = new Map<boolean, Promise<import("oidc-client-ts").UserManager>>()
  // The redirect's state (the PKCE verifier) waits in the tab's sessionStorage; the user's tokens stay there only
  // when the viewer asked to stay signed in, and in memory otherwise.
  const manager = (remember: boolean) => {
    let made = managers.get(remember)
    if (!made) {
      made = import("oidc-client-ts").then(
        ({ UserManager, WebStorageStateStore, InMemoryWebStorage }) =>
          new UserManager({
            authority: provider.issuer,
            client_id: provider.clientId,
            redirect_uri: location.origin + callbackPath,
            response_type: "code",
            scope: provider.scopes.join(" "),
            automaticSilentRenew: false,
            monitorSession: false,
            stateStore: new WebStorageStateStore({ store: sessionStorage }),
            userStore: new WebStorageStateStore({ store: remember ? sessionStorage : new InMemoryWebStorage() }),
          }),
      )
      managers.set(remember, made)
    }
    return made
  }

  const sessionOf = (user: import("oidc-client-ts").User): Session => {
    if (!user.id_token) throw new Error("The identity provider sent no ID token. Ask for the openid scope.")
    return {
      token: user.id_token,
      source: "provider",
      refreshToken: null,
      expiresAt: jwtExpiry(user.id_token) ?? (user.expires_at != null ? user.expires_at * 1000 : null),
    }
  }

  return {
    methods: signInMethods(provider),
    async signIn(_method, remember) {
      const state: OidcState = { returnTo: location.pathname + location.search, remember }
      await (await manager(true)).signinRedirect({ state })
      return null
    },
    async complete() {
      if (location.pathname !== callbackPath) return null
      const fromRedirect = await manager(true)
      const user = await fromRedirect.signinRedirectCallback()
      const state = (user.state ?? {}) as Partial<OidcState>
      const remember = state.remember !== false
      if (!remember) {
        await fromRedirect.removeUser()
        await (await manager(false)).storeUser(user)
      }
      remembered = remember
      const returnTo = typeof state.returnTo === "string" && state.returnTo.startsWith(panelPath) && !state.returnTo.startsWith(callbackPath) ? state.returnTo : `${panelPath}/`
      return { session: sessionOf(user), remember, returnTo }
    },
    async refresh() {
      const current = await manager(remembered)
      const user = await current.getUser()
      if (!user?.refresh_token) throw new Error("The identity provider gave no refresh token. Ask for the offline_access scope.")
      try {
        const next = await current.signinSilent()
        if (!next) throw new Error("The identity provider didn't renew the session.")
        return sessionOf(next)
      } catch (error) {
        if (error instanceof TypeError) throw new RefreshUnavailable("Couldn't reach the identity provider.")
        throw error
      }
    },
    async signOut() {
      await (await manager(remembered)).removeUser()
    },
  }
}

const supabaseOauthKey = "basekt-admin-ui:supabase-oauth"
const supabaseRefreshKey = "basekt-admin-ui:supabase-refresh"

interface SupabaseOauthState {
  verifier: string
  returnTo: string
  remember: boolean
}

interface SupabaseTokenResponse {
  access_token?: string
  refresh_token?: string
  expires_in?: number
  msg?: string
  error_description?: string
  message?: string
}

function supabaseClient(
  provider: SupabaseProvider,
  panelPath: string,
  fetchImpl: typeof fetch,
): ProviderClient {
  const baseUrl = provider.url.replace(/\/+$/, "")
  const callbackPath = oidcCallbackPath(panelPath)
  let memoryRefresh: string | null = null
  let memoryAccess: string | null = null

  const readRefresh = (): { token: string; remember: boolean } | null => {
    if (memoryRefresh) return { token: memoryRefresh, remember: false }
    try {
      const stored = sessionStorage.getItem(supabaseRefreshKey)
      return stored ? { token: stored, remember: true } : null
    } catch {
      return null
    }
  }

  const writeRefresh = (refreshToken: string | null, remember: boolean) => {
    if (!refreshToken) {
      memoryRefresh = null
      try {
        sessionStorage.removeItem(supabaseRefreshKey)
      } catch {
        // Storage unavailable.
      }
      return
    }
    if (remember) {
      memoryRefresh = null
      try {
        sessionStorage.setItem(supabaseRefreshKey, refreshToken)
      } catch {
        memoryRefresh = refreshToken
      }
    } else {
      memoryRefresh = refreshToken
      try {
        sessionStorage.removeItem(supabaseRefreshKey)
      } catch {
        // Storage unavailable.
      }
    }
  }

  const toSession = (payload: SupabaseTokenResponse, remember: boolean): Session => {
    const accessToken = payload.access_token
    if (!accessToken) throw new Error("Supabase returned no access token.")
    memoryAccess = accessToken
    writeRefresh(payload.refresh_token ?? null, remember)
    const expiresAt =
      jwtExpiry(accessToken) ?? (typeof payload.expires_in === "number" ? Date.now() + payload.expires_in * 1000 : null)
    return { token: accessToken, source: "provider", refreshToken: null, expiresAt }
  }

  const requestTokens = async (grantUrl: string, body: object, networkErrorMessage: string): Promise<SupabaseTokenResponse> => {
    let response: Response
    try {
      response = await fetchImpl(grantUrl, {
        method: "POST",
        headers: { "Content-Type": "application/json", apikey: provider.anonKey },
        body: JSON.stringify(body),
      })
    } catch {
      throw new Error(networkErrorMessage)
    }
    const payload = (await response.json().catch(() => ({}))) as SupabaseTokenResponse
    if (!response.ok) {
      const detail = payload.msg || payload.error_description || payload.message || ""
      if (response.status === 400 || response.status === 401) {
        if (/invalid login credentials/i.test(detail)) throw new Error("That email or password was refused.")
      }
      throw new Error(detail || `Supabase refused the sign-in (${response.status}).`)
    }
    return payload
  }

  return {
    methods: signInMethods(provider),
    async signIn(method, remember, credentials) {
      if (method === "password") {
        const payload = await requestTokens(
          `${baseUrl}/auth/v1/token?grant_type=password`,
          { email: credentials?.username ?? "", password: credentials?.password ?? "" },
          "Couldn't reach the identity provider. Check the connection and try again.",
        )
        return toSession(payload, remember)
      }
      const { verifier, challenge } = await createPkcePair()
      const state: SupabaseOauthState = { verifier, returnTo: location.pathname + location.search, remember }
      sessionStorage.setItem(supabaseOauthKey, JSON.stringify(state))
      const authUrl = new URL(`${baseUrl}/auth/v1/authorize`)
      authUrl.searchParams.set("provider", method)
      authUrl.searchParams.set("redirect_to", location.origin + callbackPath)
      authUrl.searchParams.set("code_challenge", challenge)
      authUrl.searchParams.set("code_challenge_method", "s256")
      location.assign(authUrl.toString())
      return null
    },
    async complete() {
      if (location.pathname !== callbackPath) return null
      const rawState = sessionStorage.getItem(supabaseOauthKey)
      sessionStorage.removeItem(supabaseOauthKey)
      const state = (rawState ? JSON.parse(rawState) : {}) as Partial<SupabaseOauthState>
      const remember = state.remember !== false
      const returnTo =
        typeof state.returnTo === "string" && state.returnTo.startsWith(panelPath) && !state.returnTo.startsWith(callbackPath)
          ? state.returnTo
          : `${panelPath}/`

      const searchParams = new URLSearchParams(location.search)
      const code = searchParams.get("code")
      if (code) {
        if (!state.verifier) throw new Error("Missing PKCE verifier for Supabase sign-in.")
        const payload = await requestTokens(
          `${baseUrl}/auth/v1/token?grant_type=pkce`,
          { auth_code: code, code_verifier: state.verifier },
          "Couldn't reach the identity provider to complete sign-in.",
        )
        return { session: toSession(payload, remember), remember, returnTo }
      }

      if (location.hash.startsWith("#")) {
        const hash = new URLSearchParams(location.hash.slice(1))
        const accessToken = hash.get("access_token")
        if (accessToken) {
          const expiresIn = Number(hash.get("expires_in"))
          return {
            session: toSession(
              {
                access_token: accessToken,
                refresh_token: hash.get("refresh_token") ?? undefined,
                expires_in: Number.isFinite(expiresIn) ? expiresIn : undefined,
              },
              remember,
            ),
            remember,
            returnTo,
          }
        }
      }
      return null
    },
    async refresh() {
      const current = readRefresh()
      if (!current) throw new Error("The identity provider has no refresh token in this tab.")
      let response: Response
      try {
        response = await fetchImpl(`${baseUrl}/auth/v1/token?grant_type=refresh_token`, {
          method: "POST",
          headers: { "Content-Type": "application/json", apikey: provider.anonKey },
          body: JSON.stringify({ refresh_token: current.token }),
        })
      } catch {
        throw new RefreshUnavailable("Couldn't reach the identity provider.")
      }
      const payload = (await response.json().catch(() => ({}))) as SupabaseTokenResponse
      if (!response.ok || !payload.access_token) {
        writeRefresh(null, false)
        throw new Error(payload.msg || payload.error_description || "The identity provider didn't renew the session.")
      }
      return toSession(payload, current.remember)
    },
    async signOut() {
      const token = memoryAccess
      memoryAccess = null
      writeRefresh(null, false)
      if (token) {
        await fetchImpl(`${baseUrl}/auth/v1/logout`, {
          method: "POST",
          headers: { apikey: provider.anonKey, Authorization: `Bearer ${token}` },
        }).catch(() => {})
      }
    },
  }
}

async function createPkcePair(): Promise<{ verifier: string; challenge: string }> {
  const bytes = new Uint8Array(32)
  crypto.getRandomValues(bytes)
  const verifier = base64Url(bytes)
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier))
  return { verifier, challenge: base64Url(new Uint8Array(digest)) }
}

function base64Url(bytes: Uint8Array): string {
  let binary = ""
  for (const b of bytes) binary += String.fromCharCode(b)
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
}

function once<T>(make: () => Promise<T>): () => Promise<T> {
  let made: Promise<T> | null = null
  return () => {
    made ??= make().catch((error: unknown) => {
      made = null
      throw error
    })
    return made
  }
}
