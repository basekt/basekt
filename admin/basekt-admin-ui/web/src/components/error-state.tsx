import { CircleAlert, Lock, ShieldX } from "lucide-react"
import { ApiFailure } from "@/api/client"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { usePanel } from "@/panel"

/**
 * Explains a failed call. A 401 offers to sign in, a 403 says the caller's roles don't allow it, and anything else
 * shows the server's message.
 */
export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const { requestSignIn, signedIn } = usePanel()
  if (error instanceof ApiFailure && error.unauthorized) {
    return (
      <Alert>
        <Lock />
        <AlertTitle>Sign in to see this</AlertTitle>
        <AlertDescription>
          <p>{signedIn ? "Your session was refused or has expired." : "This needs a signed-in user."}</p>
          <Button size="sm" className="mt-2" onClick={() => requestSignIn(signedIn ? "Your token was refused. Sign in again." : undefined)}>
            Sign in
          </Button>
        </AlertDescription>
      </Alert>
    )
  }
  if (error instanceof ApiFailure && error.forbidden) {
    return (
      <Alert variant="destructive">
        <ShieldX />
        <AlertTitle>You don't have access</AlertTitle>
        <AlertDescription>{error.message}</AlertDescription>
      </Alert>
    )
  }
  return (
    <Alert variant="destructive">
      <CircleAlert />
      <AlertTitle>Something went wrong</AlertTitle>
      <AlertDescription>
        <p>{error instanceof Error ? error.message : String(error)}</p>
        {onRetry && (
          <Button size="sm" variant="outline" className="mt-2" onClick={onRetry}>
            Try again
          </Button>
        )}
      </AlertDescription>
    </Alert>
  )
}
