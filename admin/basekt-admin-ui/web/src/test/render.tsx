import { render } from "@testing-library/react"
import { App } from "@/App"
import { TokenStore } from "@/auth"
import type { PanelConfig } from "@/config"
import { config } from "@/test/fixtures"

/** Renders the whole panel at [path] against [fetchImpl], with an in-memory token store and [panelConfig]. */
export function renderPanel(path: string, fetchImpl: typeof fetch, token: string | null = null, panelConfig: PanelConfig = config) {
  history.replaceState(null, "", path)
  const tokens = new TokenStore(null)
  if (token) tokens.set(token, false)
  return render(<App config={panelConfig} tokens={tokens} fetchImpl={fetchImpl} />)
}
