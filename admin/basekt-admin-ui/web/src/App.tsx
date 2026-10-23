import { useMemo, useState } from "react"
import { ADMIN_SCHEMA_VERSION, type AdminSchema } from "@wire"
import { AdminApi, ApiFailure } from "@/api/client"
import { FilesApi } from "@/api/files"
import { TokenStore, useToken } from "@/auth"
import { Home } from "@/components/home"
import { Layout } from "@/components/layout"
import { ListView } from "@/components/list-view"
import { CreateView, RecordView } from "@/components/record-view"
import { SignIn } from "@/components/sign-in"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Skeleton } from "@/components/ui/skeleton"
import { Toaster } from "@/components/ui/sonner"
import type { PanelConfig } from "@/config"
import { humanize } from "@/lib/fields"
import { useAsync } from "@/lib/use-async"
import { PanelProvider, useResource, type Panel } from "@/panel"
import { Router, useRouter } from "@/router"
import { useThemeMode } from "@/theme-mode"

/**
 * The panel: loads the caller's schema, asks for a token when the API wants one, and renders the screen the URL
 * names. Every screen is built from the schema.
 */
export function App({ config, tokens: givenTokens, fetchImpl }: {
  config: PanelConfig
  tokens?: TokenStore
  fetchImpl?: typeof fetch
}) {
  const [tokens] = useState(() => givenTokens ?? new TokenStore())
  const [mode, setMode] = useThemeMode(config.defaultMode)
  const [token, signIn, signOut] = useToken(tokens)
  const [signInReason, setSignInReason] = useState<string | null>(null)
  const api = useMemo(() => new AdminApi(config.apiPath, () => tokens.get(), fetchImpl), [config.apiPath, tokens, fetchImpl])
  const files = useMemo(() => new FilesApi(config.filesPath, () => tokens.get(), fetchImpl, undefined, api.failures), [config.filesPath, tokens, fetchImpl, api])
  const schema = useAsync(() => api.schema(), [api, token])

  const trySignIn = async (value: string, remember: boolean): Promise<string | null> => {
    try {
      const candidate = new AdminApi(config.apiPath, () => value, fetchImpl)
      await candidate.schema()
    } catch (error) {
      if (error instanceof ApiFailure && (error.unauthorized || error.forbidden)) return "That token was refused."
      return error instanceof Error ? error.message : String(error)
    }
    signIn(value, remember)
    setSignInReason(null)
    return null
  }

  const unauthorized = schema.error instanceof ApiFailure && schema.error.unauthorized
  if (signInReason !== null || unauthorized) {
    return (
      <SignIn
        brand={config.brand}
        reason={signInReason || (unauthorized ? (token ? "Your token was refused. Sign in again." : "The admin API needs you to sign in.") : undefined)}
        onSignIn={trySignIn}
        onCancel={unauthorized ? undefined : () => setSignInReason(null)}
      />
    )
  }
  if (schema.error) return <Fatal title="The admin API can't be reached" message={schema.error instanceof Error ? schema.error.message : String(schema.error)} />
  if (!schema.data) return <Loading />
  if (schema.data.version !== ADMIN_SCHEMA_VERSION) {
    return (
      <Fatal
        title="This panel doesn't match the admin API"
        message={`The API speaks schema version ${schema.data.version}; this panel speaks version ${ADMIN_SCHEMA_VERSION}. Use the same BaseKt version for basekt-admin-api and basekt-admin-ui.`}
      />
    )
  }

  const panel: Panel = {
    config,
    api,
    files,
    schema: schema.data as AdminSchema,
    signedIn: token != null,
    requestSignIn: (reason) => setSignInReason(reason ?? ""),
    signOut,
    mode,
    setMode,
    reloadSchema: schema.reload,
  }
  return (
    <PanelProvider value={panel}>
      <Router base={config.path}>
        <Layout>
          <Screen />
        </Layout>
      </Router>
      <Toaster theme={mode} position="bottom-right" />
    </PanelProvider>
  )
}

function Screen() {
  const { route } = useRouter()
  const resource = useResource(route.screen === "home" ? "" : route.resource)
  if (route.screen === "home") return <Home />
  if (!resource) {
    return (
      <Alert>
        <AlertTitle>No {humanize(route.resource)} here</AlertTitle>
        <AlertDescription>There is no such resource, or your roles don't let you see it.</AlertDescription>
      </Alert>
    )
  }
  switch (route.screen) {
    case "list":
      return <ListView resource={resource} search={route.search} />
    case "create":
      return <CreateView key={resource.name} resource={resource} />
    case "detail":
      return <RecordView key={`${resource.name}/${route.id}`} resource={resource} id={route.id} />
  }
}

function Loading() {
  return (
    <div className="flex min-h-svh" aria-busy="true">
      <div className="hidden w-60 border-r bg-sidebar p-4 md:block">
        <Skeleton className="h-6 w-32" />
      </div>
      <div className="flex-1 p-6">
        <Skeleton className="h-8 w-48" />
      </div>
    </div>
  )
}

function Fatal({ title, message }: { title: string; message: string }) {
  return (
    <main className="flex min-h-svh items-center justify-center p-6">
      <Alert variant="destructive" className="max-w-lg">
        <AlertTitle>{title}</AlertTitle>
        <AlertDescription>{message}</AlertDescription>
      </Alert>
    </main>
  )
}
