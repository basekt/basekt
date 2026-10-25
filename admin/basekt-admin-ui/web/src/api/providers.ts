import { jwtExpiry, RefreshUnavailable, type Session } from "@/auth"
import type { FirebaseProvider, OidcProvider, SignInProvider } from "@/config"

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
  return provider.providers.map((id) =>
    id === "password" ? { id, kind: "password", label: "Email" } : { id, kind: "button", label: `Continue with ${firebaseLabel(id)}` },
  )
}

/** The client for [provider], loading its SDK only when a sign-in, a callback or a refresh needs it. */
export function providerClient(provider: SignInProvider, panelPath: string): ProviderClient {
  return provider.type === "firebase" ? firebaseClient(provider) : oidcClient(provider, panelPath)
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
