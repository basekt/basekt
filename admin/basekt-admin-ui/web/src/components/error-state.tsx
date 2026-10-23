import { CircleAlert, Copy, Download, Lock, ShieldX, WifiOff } from "lucide-react"
import { toast } from "sonner"
import { ApiFailure } from "@/api/client"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { errorLog, errorLogName, errorReport } from "@/lib/error-report"
import { usePanel } from "@/panel"

/**
 * Explains a failed call. A 401 offers to sign in, a 403 says the caller's roles don't allow it, a call that got no
 * answer says the server couldn't be reached, and anything else shows the server's message with its request id. Those
 * last two offer to copy the details of the failure, or download the log of this page's recent failures, to report it.
 */
export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const { api, requestSignIn, signedIn } = usePanel()
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
  const failure = error instanceof ApiFailure ? error : null
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(errorReport(error))
      toast.success("Details copied. Paste them where you report the problem.")
    } catch {
      toast.error("The browser didn't allow copying. Download the log instead.")
    }
  }
  const download = () => {
    const url = URL.createObjectURL(new Blob([errorLog(api.failures.entries())], { type: "application/json" }))
    const link = Object.assign(document.createElement("a"), { href: url, download: errorLogName() })
    link.click()
    URL.revokeObjectURL(url)
  }
  return (
    <Alert variant="destructive">
      {failure?.network ? <WifiOff /> : <CircleAlert />}
      <AlertTitle>{failure?.network ? "Couldn't reach the server" : "Something went wrong"}</AlertTitle>
      <AlertDescription>
        <p>
          {failure?.network
            ? "No answer came back. Check the connection and try again; if it keeps happening, send the details to whoever runs this panel."
            : error instanceof Error ? error.message : String(error)}
        </p>
        {failure?.requestId && (
          <p className="text-xs">
            Request id: <code className="select-all">{failure.requestId}</code>
          </p>
        )}
        <div className="mt-2 flex flex-wrap gap-2">
          {onRetry && (
            <Button size="sm" variant="outline" onClick={onRetry}>
              Try again
            </Button>
          )}
          <Button size="sm" variant="ghost" onClick={copy}>
            <Copy /> Copy details
          </Button>
          <Button size="sm" variant="ghost" onClick={download}>
            <Download /> Download log
          </Button>
        </div>
      </AlertDescription>
    </Alert>
  )
}
