import type { ReactNode } from "react"
import { Database, ExternalLink, LogIn, LogOut, Table2 } from "lucide-react"
import { ModeToggle } from "@/components/mode-toggle"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { humanize } from "@/lib/fields"
import { cn } from "@/lib/utils"
import { isSafeHref } from "@/lib/links"
import { menuSections } from "@/lib/menu"
import { usePanel } from "@/panel"
import { Link, useRouter } from "@/router"

/** The sidebar of the resources the caller may see, the session controls, and the current screen. */
export function Layout({ children }: { children: ReactNode }) {
  const { config, schema, signedIn, requestSignIn, signOut, mode, setMode } = usePanel()
  const { route } = useRouter()
  const current = route.screen === "home" ? null : route.resource
  return (
    <div className="flex min-h-svh flex-col md:flex-row">
      <aside className="flex shrink-0 flex-col border-b border-sidebar-border bg-sidebar text-sidebar-foreground md:sticky md:top-0 md:h-svh md:w-60 md:border-r md:border-b-0">
        <Link to={{ screen: "home" }} className="flex items-center gap-2 px-4 py-4 font-semibold">
          {config.brand.logo ? <img src={config.brand.logo} alt="" className="size-6" /> : <Database className="size-5" />}
          <span className="truncate">{config.brand.name}</span>
        </Link>
        <nav aria-label="Resources" className="flex gap-1 overflow-x-auto px-2 pb-2 md:flex-1 md:flex-col md:overflow-y-auto">
          {menuSections(schema.resources).map((section) => (
            <div key={section.group ?? ""} role="group" aria-label={section.group ?? undefined} className="flex gap-1 md:flex-col">
              {section.group && (
                <h2 className="hidden px-3 pt-3 pb-1 text-xs font-medium uppercase tracking-wide text-muted-foreground md:block">{section.group}</h2>
              )}
              {section.resources.map((resource) => (
                <Link
                  key={resource.name}
                  to={{ screen: "list", resource: resource.name, search: new URLSearchParams() }}
                  aria-current={current === resource.name ? "page" : undefined}
                  className={cn(
                    "flex shrink-0 items-center gap-2 rounded-md px-3 py-2 text-sm hover:bg-sidebar-accent hover:text-sidebar-accent-foreground",
                    current === resource.name && "bg-sidebar-accent font-medium text-sidebar-accent-foreground",
                  )}
                >
                  <Table2 className="size-4 opacity-70" />
                  {humanize(resource.name)}
                </Link>
              ))}
            </div>
          ))}
        </nav>
        <div className="hidden border-t border-sidebar-border p-3 text-sm md:block">
          {schema.caller ? (
            <div className="mb-2 grid gap-1">
              <span className="truncate font-medium">{schema.caller.id}</span>
              <span className="flex flex-wrap gap-1">
                {schema.caller.roles.map((role) => (
                  <Badge key={role} variant="secondary">
                    {role}
                  </Badge>
                ))}
              </span>
            </div>
          ) : (
            <p className="mb-2 text-muted-foreground">Not signed in</p>
          )}
        </div>
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex items-center justify-end gap-1 border-b px-4 py-2">
          {config.siteLink && isSafeHref(config.siteLink.url) && (
            <Button variant="ghost" size="sm" className="mr-auto" asChild>
              <a href={config.siteLink.url} target="_blank" rel="noopener noreferrer">
                <ExternalLink /> {config.siteLink.label}
              </a>
            </Button>
          )}
          <ModeToggle mode={mode} setMode={setMode} />
          {signedIn ? (
            <Button variant="ghost" size="sm" onClick={signOut}>
              <LogOut /> Sign out
            </Button>
          ) : (
            <Button variant="ghost" size="sm" onClick={() => requestSignIn()}>
              <LogIn /> Sign in
            </Button>
          )}
        </header>
        <main className="flex-1 p-4 md:p-6">{children}</main>
      </div>
    </div>
  )
}
