import { render } from "@testing-library/react"
import type { ReactNode } from "react"
import type { AdminSchema } from "@wire"
import { AdminApi } from "@/api/client"
import { FilesApi } from "@/api/files"
import { PanelProvider, type Panel } from "@/panel"
import { Router } from "@/router"
import { config } from "@/test/fixtures"

/** Renders [ui] inside a panel for [schema], calling the API through [fetchImpl]. */
export function renderInPanel(ui: ReactNode, schema: AdminSchema, fetchImpl: typeof fetch, xhr?: () => XMLHttpRequest) {
  const panel: Panel = {
    config,
    api: new AdminApi(config.apiPath, () => null, fetchImpl),
    files: new FilesApi(config.filesPath, () => null, fetchImpl, xhr),
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
