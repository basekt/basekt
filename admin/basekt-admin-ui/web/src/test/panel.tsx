import { render } from "@testing-library/react"
import type { ReactNode } from "react"
import type { AdminSchema } from "@wire"
import { AdminApi } from "@/api/client"
import { PanelProvider, type Panel } from "@/panel"
import { Router } from "@/router"
import { config } from "@/test/fixtures"

/** Renders [ui] inside a panel for [schema], calling the API through [fetchImpl]. */
export function renderInPanel(ui: ReactNode, schema: AdminSchema, fetchImpl: typeof fetch) {
  const panel: Panel = {
    config,
    api: new AdminApi(config.apiPath, () => null, fetchImpl),
    schema,
    signedIn: false,
    requestSignIn: () => {},
    signOut: () => {},
    mode: "light",
    setMode: () => {},
    reloadSchema: () => {},
  }
  return render(
    <PanelProvider value={panel}>
      <Router base={config.path}>{ui}</Router>
    </PanelProvider>,
  )
}
