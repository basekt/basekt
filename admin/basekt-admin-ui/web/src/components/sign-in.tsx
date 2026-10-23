import { useState, type FormEvent } from "react"
import { KeyRound } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import type { Brand } from "@/config"

/**
 * Asks for a bearer token. A host app that uses cookie sessions signs the viewer in on its own pages; the panel
 * sends those cookies with every call, so this screen is for token-based hosts.
 */
export function SignIn({ brand, reason, onSignIn, onCancel }: {
  brand: Brand
  reason?: string
  onSignIn: (token: string, remember: boolean) => Promise<string | null>
  onCancel?: () => void
}) {
  const [token, setToken] = useState("")
  const [remember, setRemember] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!token.trim()) return
    setBusy(true)
    setError(await onSignIn(token.trim(), remember))
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
            <CardDescription>{reason ?? "Paste an access token that the host app gave you."}</CardDescription>
          </CardHeader>
          <CardContent className="mt-4 grid gap-4">
            {error && (
              <Alert variant="destructive">
                <AlertDescription>{error}</AlertDescription>
              </Alert>
            )}
            <div className="grid gap-2">
              <Label htmlFor="token">Access token</Label>
              <Input id="token" type="password" autoComplete="off" value={token} onChange={(e) => setToken(e.target.value)} autoFocus />
            </div>
            <label className="flex items-center gap-2 text-sm text-muted-foreground">
              <Checkbox checked={remember} onCheckedChange={(v) => setRemember(v === true)} />
              Keep me signed in in this tab
            </label>
          </CardContent>
          <CardFooter className="mt-6 flex justify-end gap-2">
            {onCancel && (
              <Button type="button" variant="ghost" onClick={onCancel}>
                Cancel
              </Button>
            )}
            <Button type="submit" disabled={busy || !token.trim()}>
              Sign in
            </Button>
          </CardFooter>
        </form>
      </Card>
    </main>
  )
}
