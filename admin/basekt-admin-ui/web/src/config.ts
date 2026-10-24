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
}

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
  siteLink: null,
}

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
    login: parsed.login ?? null,
    siteLink: parsed.siteLink ?? null,
  }
}

function trimSlash(path: string): string {
  return path.length > 1 ? path.replace(/\/+$/, "") : path
}
