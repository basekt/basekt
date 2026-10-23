import { Table2 } from "lucide-react"
import { Card, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { humanize } from "@/lib/fields"
import { usePanel } from "@/panel"
import { Link } from "@/router"

/** The start screen: a card for each resource the caller may see. */
export function Home() {
  const { schema, config, requestSignIn } = usePanel()
  if (schema.resources.length === 0) {
    return (
      <div className="mx-auto max-w-md py-16 text-center">
        <h1 className="text-xl font-semibold">Nothing to show yet</h1>
        <p className="mt-2 text-muted-foreground">
          {schema.caller ? "Your roles don't give you access to any resource." : "Sign in to see the resources you can manage."}
        </p>
        {!schema.caller && (
          <button type="button" className="mt-4 text-primary underline-offset-4 hover:underline" onClick={() => requestSignIn()}>
            Sign in
          </button>
        )}
      </div>
    )
  }
  return (
    <div className="grid gap-6">
      <h1 className="text-2xl font-semibold tracking-tight">{config.brand.name}</h1>
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {schema.resources.map((resource) => (
          <Link key={resource.name} to={{ screen: "list", resource: resource.name, search: new URLSearchParams() }} className="group">
            <Card className="transition-colors group-hover:bg-accent">
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <Table2 className="size-4 text-muted-foreground" />
                  {humanize(resource.name)}
                </CardTitle>
                <CardDescription>
                  {resource.fields.length} fields · {resource.operations.join(", ")}
                </CardDescription>
              </CardHeader>
            </Card>
          </Link>
        ))}
      </div>
    </div>
  )
}
