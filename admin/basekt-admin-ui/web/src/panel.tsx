import { createContext, useContext, type ReactNode } from "react"
import type { AdminResource, AdminSchema, Operation } from "@wire"
import type { AdminApi } from "@/api/client"
import type { PanelConfig, ThemeMode } from "@/config"

/** What every screen reads: the API, the caller's schema, and the viewer's session and mode. */
export interface Panel {
  config: PanelConfig
  api: AdminApi
  schema: AdminSchema
  /** Whether the viewer entered a bearer token. Cookie sessions don't count: the panel can't see them. */
  signedIn: boolean
  /** Asks for the sign-in screen, with an optional reason. */
  requestSignIn: (reason?: string) => void
  signOut: () => void
  mode: ThemeMode
  setMode: (mode: ThemeMode) => void
  /** Loads the schema again, after a write that may change what the caller sees. */
  reloadSchema: () => void
}

const PanelContext = createContext<Panel | null>(null)

export function PanelProvider({ value, children }: { value: Panel; children: ReactNode }) {
  return <PanelContext.Provider value={value}>{children}</PanelContext.Provider>
}

export function usePanel(): Panel {
  const value = useContext(PanelContext)
  if (!value) throw new Error("usePanel needs a <PanelProvider>.")
  return value
}

/** The resource called [name] in the caller's schema, or undefined when they may not see it. */
export function useResource(name: string): AdminResource | undefined {
  return usePanel().schema.resources.find((r) => r.name === name)
}

/** Whether the schema lets the caller do [operation] on [resource]. The panel hides what it doesn't. */
export function can(resource: AdminResource, operation: Operation): boolean {
  return resource.operations.includes(operation)
}
