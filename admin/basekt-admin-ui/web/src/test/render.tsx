import { render } from "@testing-library/react"
import { App } from "@/App"
import { TokenStore } from "@/auth"
import { config } from "@/test/fixtures"

/** Renders the whole panel at [path] against [fetchImpl], with an in-memory token store. */
export function renderPanel(path: string, fetchImpl: typeof fetch, token: string | null = null) {
  history.replaceState(null, "", path)
  const tokens = new TokenStore(null)
  if (token) tokens.set(token, false)
  return render(<App config={config} tokens={tokens} fetchImpl={fetchImpl} />)
}
