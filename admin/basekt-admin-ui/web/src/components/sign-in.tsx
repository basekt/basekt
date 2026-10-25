import { useState, type FormEvent } from "react"
import { KeyRound } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import type { SignInMethod } from "@/api/providers"
import type { Brand, LoginRoute } from "@/config"

/**
 * Signs the viewer in: through an identity provider's [methods] when the panel has one, with a name and a password
 * through the host's [login] route when it has one, or with a pasted bearer token, which stays one click away. A host
 * app that uses cookie sessions signs the viewer in on its own pages; the panel sends those cookies with every call, so
 * this screen is for token-based hosts.
 */
export function SignIn({ brand, reason, login = null, methods = [], onSignIn, onPasswordSignIn, onProviderSignIn, onCancel }: {
  brand: Brand
  reason?: string
  login?: LoginRoute | null
  methods?: SignInMethod[]
  onSignIn: (token: string, remember: boolean) => Promise<string | null>
  onPasswordSignIn?: (username: string, password: string, remember: boolean) => Promise<string | null>
  onProviderSignIn?: (method: string, remember: boolean, credentials?: { username: string; password: string }) => Promise<string | null>
  onCancel?: () => void
}) {
  const buttons = onProviderSignIn ? methods.filter((m) => m.kind === "button") : []
  const providerPassword = onProviderSignIn ? methods.find((m) => m.kind === "password") : undefined
  const routePassword = login !== null && onPasswordSignIn !== undefined && !providerPassword
  const passwordForm = providerPassword ? { label: providerPassword.label, email: true } : routePassword && login ? { label: login.usernameLabel, email: login.usernameField === "email" } : null
  const hasAccount = passwordForm !== null || buttons.length > 0
  const [withAccount, setWithAccount] = useState(hasAccount)
  const withPassword = withAccount && passwordForm !== null
  const [token, setToken] = useState("")
  const [username, setUsername] = useState("")
  const [password, setPassword] = useState("")
  const [remember, setRemember] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const ready = withAccount ? withPassword && username.trim() !== "" && password !== "" : token.trim() !== ""

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!ready) return
    setBusy(true)
    if (!withAccount) setError(await onSignIn(token.trim(), remember))
    else if (providerPassword && onProviderSignIn) setError(await onProviderSignIn(providerPassword.id, remember, { username: username.trim(), password }))
    else if (onPasswordSignIn) setError(await onPasswordSignIn(username.trim(), password, remember))
    setBusy(false)
  }

  const viaProvider = async (method: string) => {
    if (!onProviderSignIn) return
    setBusy(true)
    setError(await onProviderSignIn(method, remember))
    setBusy(false)
  }

  return (
    <main className="flex min-h-svh items-center justify-center bg-muted/40 p-6">
      <Card className="w-full max-w-sm">
        <form onSubmit={submit}>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              {brand.logo ? <img src={brand.logo} alt="" className="size-6" /> : <KeyRound className="size-5" />}
              Sign in to {brand.name}
            </CardTitle>
            <CardDescription>
              {reason ?? (withAccount ? "Sign in with your account." : "Paste an access token that the host app gave you.")}
            </CardDescription>
          </CardHeader>
          <CardContent className="mt-4 grid gap-4">
            {error && (
              <Alert variant="destructive">
                <AlertDescription>{error}</AlertDescription>
              </Alert>
            )}
            {withAccount && buttons.length > 0 && (
              <div className="grid gap-2">
                {buttons.map((method) => (
                  <Button key={method.id} type="button" variant="outline" disabled={busy} onClick={() => void viaProvider(method.id)}>
                    {method.label}
                  </Button>
                ))}
              </div>
            )}
            {withAccount && passwordForm && (
              <>
                <div className="grid gap-2">
                  <Label htmlFor="username">{passwordForm.label}</Label>
                  <Input
                    id="username"
                    type={passwordForm.email ? "email" : "text"}
                    autoComplete="username"
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                    autoFocus
                  />
                </div>
                <div className="grid gap-2">
                  <Label htmlFor="password">Password</Label>
                  <Input id="password" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
                </div>
              </>
            )}
            {!withAccount && (
              <div className="grid gap-2">
                <Label htmlFor="token">Access token</Label>
                <Input id="token" type="password" autoComplete="off" value={token} onChange={(e) => setToken(e.target.value)} autoFocus />
              </div>
            )}
            <label className="flex items-center gap-2 text-sm text-muted-foreground">
              <Checkbox checked={remember} onCheckedChange={(v) => setRemember(v === true)} />
              Keep me signed in in this tab
            </label>
          </CardContent>
          <CardFooter className="mt-6 flex justify-end gap-2">
            {hasAccount && (
              <Button
                type="button"
                variant="link"
                className="mr-auto px-0"
                onClick={() => {
                  setWithAccount(!withAccount)
                  setError(null)
                }}
              >
                {withAccount ? "Use an access token" : passwordForm && buttons.length === 0 ? `Use ${passwordForm.label.toLowerCase()} and password` : "Use your account"}
              </Button>
            )}
            {onCancel && (
              <Button type="button" variant="ghost" onClick={onCancel}>
                Cancel
              </Button>
            )}
            {(!withAccount || withPassword) && (
              <Button type="submit" disabled={busy || !ready}>
                Sign in
              </Button>
            )}
          </CardFooter>
        </form>
      </Card>
    </main>
  )
}
