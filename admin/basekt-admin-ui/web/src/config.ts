/** How the panel starts: light, dark, or following the operating system. */
export type ThemeMode = "light" | "dark" | "system"

/** The host app's brand, shown in the sidebar and the page title. */
export interface Brand {
  name: string
  logo: string | null
}

/** The host's login route, which trades a name and a password for a bearer token. Mirrors the plugin's `ShellLogin`. */
export interface LoginRoute {
  /** The route's path on the panel's server, such as `/auth/login`. */
  path: string
  usernameField: string
  usernameLabel: string
  passwordField: string
  tokenField: string
  /**
   * The host's refresh route on the panel's server, such as `/auth/refresh`, or null when tokens aren't refreshed.
   * Only with one does the panel keep a refresh token, and it sends it nowhere else.
   */
  refreshPath: string | null
  /** The JSON property of the answers that holds the refresh token, and of the refresh call's body. */
  refreshTokenField: string
  /** The JSON property of the answers that holds the token's life in seconds. */
  expiresInField: string
}

/** Sign-in through Firebase Authentication. Mirrors the plugin's `ShellFirebase`. */
export interface FirebaseProvider {
  type: "firebase"
  /** The Firebase Web config's API key, which identifies the project and is public by design. */
  apiKey: string
  authDomain: string
  projectId: string | null
  /** Firebase provider ids: `password` for an email and a password, or ones such as `google.com` for a popup. */
  providers: string[]
}

/** Sign-in through an OpenID Connect issuer, with the authorization code flow and PKCE. Mirrors `ShellOidc`. */
export interface OidcProvider {
  type: "oidc"
  /** The issuer's URL, where its `.well-known/openid-configuration` is found. */
  issuer: string
  clientId: string
  scopes: string[]
  /** What the sign-in button names, as in "Continue with {label}". */
  label: string
}

/** Sign-in through Supabase Auth (GoTrue). Mirrors the plugin's `ShellSupabase`. */
export interface SupabaseProvider {
  type: "supabase"
  /** The Supabase project's URL, such as `https://xyzcompany.supabase.co`. */
  url: string
  /** The project's public anon key. */
  anonKey: string
  /** `password` for an email and a password, or OAuth provider names such as `google`, `github` or `discord`. */
  providers: string[]
}

/** An identity provider the panel signs in with, sending its ID or access token as the bearer token. */
export type SignInProvider = FirebaseProvider | OidcProvider | SupabaseProvider

/** A link from the panel to the host app's own site. Mirrors the plugin's `SiteLink`. */
export interface SiteLink {
  label: string
  /** A path on the panel's server, or an http or https address. */
  url: string
}

/**
 * What the Ktor plugin tells the panel, in the shell page's `basekt-admin-config` JSON script. The plugin's
 * `ShellConfig` writes it; keep the two in step.
 */
export interface PanelConfig {
  /** Where the panel is mounted, such as `/admin`. Its own URLs live under it. */
  path: string
  /** Where the JSON admin API is mounted, such as `/admin/api`. */
  apiPath: string
  /** Where BaseKtFiles serves files, such as `/files`, for file fields. */
  filesPath: string
  brand: Brand
  defaultMode: ThemeMode
  /** Where viewers sign in with a name and a password, or null to paste a token only. */
  login: LoginRoute | null
  /** An identity provider to sign in with, or null for none. */
  provider: SignInProvider | null
  /** A link to the host's site for the top bar, or null for none. */
  siteLink: SiteLink | null
}

const fallback: PanelConfig = {
  path: "/admin",
  apiPath: "/admin/api",
  filesPath: "/files",
  brand: { name: "BaseKt", logo: null },
  defaultMode: "system",
  login: null,
  provider: null,
  siteLink: null,
}

// What an older plugin's login leaves out.
const loginDefaults = { refreshPath: null, refreshTokenField: "refreshToken", expiresInField: "expiresIn" }

/** Reads the config the shell page carries, or the defaults when there is none. */
export function readConfig(doc: Document = document): PanelConfig {
  const text = doc.getElementById("basekt-admin-config")?.textContent
  if (!text) return fallback
  const parsed = JSON.parse(text) as Partial<PanelConfig>
  return {
    path: trimSlash(parsed.path ?? fallback.path),
    apiPath: trimSlash(parsed.apiPath ?? fallback.apiPath),
    filesPath: trimSlash(parsed.filesPath ?? fallback.filesPath),
    brand: { ...fallback.brand, ...parsed.brand },
    defaultMode: parsed.defaultMode ?? fallback.defaultMode,
    login: parsed.login ? { ...loginDefaults, ...parsed.login } : null,
    provider: parsed.provider ?? null,
    siteLink: parsed.siteLink ?? null,
  }
}

function trimSlash(path: string): string {
  return path.length > 1 ? path.replace(/\/+$/, "") : path
}
