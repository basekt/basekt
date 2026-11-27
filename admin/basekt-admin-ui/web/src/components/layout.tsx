import { useState, type ReactNode } from "react"
import { Database, ExternalLink, LayoutDashboard, LogIn, LogOut, Menu } from "lucide-react"
import { ModeToggle } from "@/components/mode-toggle"
import { ResourceIcon } from "@/components/resource-icon"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogTitle } from "@/components/ui/dialog"
import { humanize } from "@/lib/fields"
import { cn } from "@/lib/utils"
import { isSafeHref } from "@/lib/links"
import { menuSections } from "@/lib/menu"
import { usePanel } from "@/panel"
import { Link, useRouter, type CustomPage } from "@/router"

/**
 * The sidebar of the resources the caller may see, the session controls, and the current screen. Below the `md`
 * breakpoint the sidebar is a drawer behind a menu button, so every section and heading stays in view however many
 * resources there are.
 */
export function Layout({ children, customPages = [] }: { children: ReactNode; customPages?: readonly CustomPage[] }) {
  const { config, signedIn, requestSignIn, signOut, mode, setMode } = usePanel()
  const [menuOpen, setMenuOpen] = useState(false)

  return (
    <div className="flex min-h-svh flex-col md:flex-row">
      <aside className="hidden shrink-0 flex-col border-r border-sidebar-border bg-sidebar text-sidebar-foreground md:sticky md:top-0 md:flex md:h-svh md:w-60">
        <Sidebar customPages={customPages} />
      </aside>
      <Dialog open={menuOpen} onOpenChange={setMenuOpen}>
        <DialogContent
          aria-describedby={undefined}
          className="top-0 left-0 flex h-full w-72 max-w-[85vw] translate-x-0 translate-y-0 flex-col gap-0 rounded-none border-r border-sidebar-border bg-sidebar p-0 text-sidebar-foreground data-[state=closed]:slide-out-to-left data-[state=closed]:zoom-out-100 data-[state=open]:slide-in-from-left data-[state=open]:zoom-in-100 sm:max-w-72"
        >
          <DialogTitle className="sr-only">Menu</DialogTitle>
          <Sidebar customPages={customPages} onNavigate={() => setMenuOpen(false)} />
        </DialogContent>
      </Dialog>
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex items-center gap-1 border-b px-4 py-2">
          <div className="mr-auto flex min-w-0 items-center gap-1">
            <Button variant="ghost" size="icon" className="md:hidden" aria-label="Open the menu" onClick={() => setMenuOpen(true)}>
              <Menu />
            </Button>
            <span className="truncate font-semibold md:hidden">{config.brand.name}</span>
            {config.siteLink && isSafeHref(config.siteLink.url) && (
              <Button variant="ghost" size="sm" className="hidden sm:inline-flex" asChild>
                <a href={config.siteLink.url} target="_blank" rel="noopener noreferrer">
                  <ExternalLink /> {config.siteLink.label}
                </a>
              </Button>
            )}
          </div>
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

/** The brand, the menu of resources and custom pages under their headings, and who is signed in. */
function Sidebar({ customPages, onNavigate }: { customPages: readonly CustomPage[]; onNavigate?: () => void }) {
  const { config, schema } = usePanel()
  const { route } = useRouter()
  const currentResource = route.screen === "home" || route.screen === "page" ? null : route.resource
  const currentPage = route.screen === "page" ? route.page : null
  const resourceSections = menuSections(schema.resources)
  const knownGroups = new Set(resourceSections.map((s) => s.group ?? ""))
  const extraPageGroups = Array.from(
    new Set(customPages.map((p) => p.group ?? "").filter((g) => g !== "" && !knownGroups.has(g))),
  )
  const topPages = customPages.filter((p) => !p.group)
  const pageLink = (page: CustomPage) => (
    <CustomPageLink key={page.id} page={page} active={currentPage === page.id} onClick={onNavigate} />
  )

  return (
    <>
      <Link to={{ screen: "home" }} onClick={onNavigate} className="flex items-center gap-2 px-4 py-4 font-semibold">
        {config.brand.logo ? <img src={config.brand.logo} alt="" className="size-6" /> : <Database className="size-5" />}
        <span className="truncate">{config.brand.name}</span>
      </Link>
      <nav aria-label="Resources" className="flex flex-1 flex-col gap-1 overflow-y-auto px-2 pb-2">
        {topPages.length > 0 && (
          <div role="group" className="flex flex-col gap-1">
            {topPages.map(pageLink)}
          </div>
        )}
        {resourceSections.map((section) => {
          const sectionPages = section.group ? customPages.filter((p) => p.group === section.group) : []
          return (
            <div key={section.group ?? ""} role="group" aria-label={section.group ?? undefined} className="flex flex-col gap-1">
              {section.group && <SectionHeading>{section.group}</SectionHeading>}
              {sectionPages.map(pageLink)}
              {section.resources.map((resource) => (
                <Link
                  key={resource.name}
                  to={{ screen: "list", resource: resource.name, search: new URLSearchParams() }}
                  onClick={onNavigate}
                  aria-current={currentResource === resource.name ? "page" : undefined}
                  className={cn(
                    "flex shrink-0 items-center gap-2 rounded-md px-3 py-2 text-sm hover:bg-sidebar-accent hover:text-sidebar-accent-foreground",
                    currentResource === resource.name && "bg-sidebar-accent font-medium text-sidebar-accent-foreground",
                  )}
                >
                  <ResourceIcon name={resource.view?.icon} className="size-4 shrink-0 opacity-70" />
                  {humanize(resource.name)}
                </Link>
              ))}
            </div>
          )
        })}
        {extraPageGroups.map((group) => (
          <div key={group} role="group" aria-label={group} className="flex flex-col gap-1">
            <SectionHeading>{group}</SectionHeading>
            {customPages.filter((p) => p.group === group).map(pageLink)}
          </div>
        ))}
      </nav>
      <div className="border-t border-sidebar-border p-3 text-sm">
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
    </>
  )
}

function SectionHeading({ children }: { children: ReactNode }) {
  return <h2 className="px-3 pt-3 pb-1 text-xs font-medium uppercase tracking-wide text-muted-foreground">{children}</h2>
}

function CustomPageLink({ page, active, onClick }: { page: CustomPage; active: boolean; onClick?: () => void }) {
  return (
    <Link
      to={{ screen: "page", page: page.id, search: new URLSearchParams() }}
      onClick={onClick}
      aria-current={active ? "page" : undefined}
      className={cn(
        "flex shrink-0 items-center gap-2 rounded-md px-3 py-2 text-sm hover:bg-sidebar-accent hover:text-sidebar-accent-foreground",
        active && "bg-sidebar-accent font-medium text-sidebar-accent-foreground",
      )}
    >
      {page.icon ?? <LayoutDashboard className="size-4 opacity-70" />}
      {page.label}
    </Link>
  )
}
