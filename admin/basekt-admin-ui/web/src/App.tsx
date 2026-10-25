import { useEffect, useMemo, useState } from "react"
import { ADMIN_SCHEMA_VERSION, type AdminSchema } from "@wire"
import { AdminApi, ApiFailure } from "@/api/client"
import { FilesApi } from "@/api/files"
import { oidcCallbackPath, providerClient, SignInCancelled, type ProviderClient } from "@/api/providers"
import { pastedSession, TokenStore, useSession, type Clock, type Refresh, type Session } from "@/auth"
import { Home } from "@/components/home"
import { Layout } from "@/components/layout"
import { ListView } from "@/components/list-view"
import { CreateView, RecordView } from "@/components/record-view"
import { SignIn } from "@/components/sign-in"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Skeleton } from "@/components/ui/skeleton"
import { Toaster } from "@/components/ui/sonner"
import { passwordLogin, refreshLogin } from "@/api/login"
import type { PanelConfig } from "@/config"
import { humanize } from "@/lib/fields"
import { useAsync } from "@/lib/use-async"
import { PanelProvider, useResource, type Panel } from "@/panel"
import { Router, useRouter } from "@/router"
import { useThemeMode } from "@/theme-mode"

/**
 * The panel: loads the caller's schema, asks for a token when the API wants one, and renders the screen the URL
 * names. Every screen is built from the schema. A session from the host's login route or an identity provider is
 * refreshed before it expires, and ends with a clean sign-out when it can't be.
 */
export function App({ config, tokens: givenTokens, fetchImpl, provider: givenProvider, clock }: {
  config: PanelConfig
  tokens?: TokenStore
  fetchImpl?: typeof fetch
  /** The identity provider's client; by default one for `config.provider`. */
  provider?: ProviderClient | null
  clock?: Clock
}) {
  const [tokens] = useState(() => givenTokens ?? new TokenStore())
  const [provider] = useState(() => (givenProvider !== undefined ? givenProvider : config.provider ? providerClient(config.provider, config.path) : null))
  const [mode, setMode] = useThemeMode(config.defaultMode)
  const login = config.login
  const now = () => (clock ? clock.now() : Date.now())
  // A refresh token goes back only to the route that gave it, and a provider's session only to the provider.
  const refreshFor = (session: Session): Refresh | null => {
    if (session.source === "login" && login?.refreshPath && session.refreshToken) return (s) => refreshLogin(login, s, fetchImpl, now)
    if (session.source === "provider" && provider) return (s) => provider.refresh(s)
    return null
  }
  const providerSignOut = (session: Session) => {
    if (session.source === "provider") provider?.signOut().catch(() => {})
  }
  const { session, renewing, signIn, signOut, ended } = useSession(tokens, refreshFor, providerSignOut, clock)
  const token = session?.token ?? null
  const [completing, setCompleting] = useState(() => provider !== null && config.provider?.type === "oidc" && location.pathname === oidcCallbackPath(config.path))
  const [signInReason, setSignInReason] = useState<string | null>(null)
  const waiting = renewing || completing
  const api = useMemo(() => new AdminApi(config.apiPath, () => tokens.get(), fetchImpl), [config.apiPath, tokens, fetchImpl])
  const files = useMemo(() => new FilesApi(config.filesPath, () => tokens.get(), fetchImpl, undefined, api.failures), [config.filesPath, tokens, fetchImpl, api])
  const schema = useAsync(() => (waiting ? new Promise<never>(() => {}) : api.schema()), [api, token, waiting])

  const trySession = async (value: Session, remember: boolean, refused: string): Promise<string | null> => {
    try {
      const candidate = new AdminApi(config.apiPath, () => value.token, fetchImpl)
      await candidate.schema()
    } catch (error) {
      if (error instanceof ApiFailure && (error.unauthorized || error.forbidden)) return refused
      return error instanceof Error ? error.message : String(error)
    }
    signIn(value, remember)
    setSignInReason(null)
    return null
  }

  const trySignIn = (value: string, remember: boolean) => trySession(pastedSession(value), remember, "That token was refused.")

  const tryPasswordSignIn = async (username: string, password: string, remember: boolean): Promise<string | null> => {
    if (!login) return null
    let value: Session
    try {
      value = await passwordLogin(login, username, password, fetchImpl, now)
    } catch (error) {
      if (error instanceof ApiFailure && error.network) return "Couldn't reach the server. Check the connection and try again."
      if (error instanceof ApiFailure && (error.unauthorized || error.forbidden)) return error.error?.message ?? "That name or password was refused."
      return error instanceof Error ? error.message : String(error)
    }
    return trySession(value, remember, "The admin API refused that account.")
  }

  const tryProviderSignIn = async (method: string, remember: boolean, credentials?: { username: string; password: string }): Promise<string | null> => {
    if (!provider) return null
    let value: Session | null
    try {
      value = await provider.signIn(method, remember, credentials)
    } catch (error) {
      if (error instanceof SignInCancelled) return null
      return error instanceof Error ? error.message : String(error)
    }
    if (value === null) return null // The browser is on its way to the provider.
    const refused = await trySession(value, remember, "The admin API refused that account.")
    if (refused !== null) provider.signOut().catch(() => {})
    return refused
  }

  useEffect(() => {
    if (!completing || !provider) return
    let current = true
    provider.complete().then(
      async (done) => {
        if (!current) return
        if (done) {
          history.replaceState(null, "", done.returnTo)
          const refused = await trySession(done.session, done.remember, "The admin API refused that account.")
          if (refused !== null) {
            provider.signOut().catch(() => {})
            setSignInReason(refused)
          }
        }
        setCompleting(false)
      },
      (error: unknown) => {
        if (!current) return
        history.replaceState(null, "", `${config.path}/`)
        setSignInReason(error instanceof Error ? `Sign-in didn't finish: ${error.message}` : "Sign-in didn't finish.")
        setCompleting(false)
      },
    )
    return () => {
      current = false
    }
    // Runs once, for the redirect the page was opened with.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [completing, provider])

  if (waiting) return <Loading />
  const unauthorized = schema.error instanceof ApiFailure && schema.error.unauthorized
  if (signInReason !== null || unauthorized) {
    return (
      <SignIn
        brand={config.brand}
        reason={signInReason || (unauthorized ? (token ? "Your token was refused. Sign in again." : (ended ?? "The admin API needs you to sign in.")) : undefined)}
        login={login}
        methods={provider?.methods}
        onSignIn={trySignIn}
        onPasswordSignIn={tryPasswordSignIn}
        onProviderSignIn={provider ? tryProviderSignIn : undefined}
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
