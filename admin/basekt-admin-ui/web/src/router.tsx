import { createContext, useCallback, useContext, useEffect, useMemo, useState, type AnchorHTMLAttributes, type MouseEvent, type ReactNode } from "react"

/** An extra screen supplied by a host app, listed in the sidebar and routed under the panel's base path. */
export interface CustomPage {
  /** Unique page identifier. */
  id: string
  /** Label shown in the sidebar navigation. */
  label: string
  /** Optional sidebar heading; when omitted, listed at the top of the sidebar. */
  group?: string
  /** Path under the panel's base path, such as `/live` (defaults to `/p/{id}`). */
  path?: string
  /** Optional icon shown beside the label in the sidebar. */
  icon?: ReactNode
  /** Renders the page content inside the panel's layout. */
  render: (search: URLSearchParams) => ReactNode
}

/** A screen of the panel, parsed from the URL under the panel's path. */
export type Route =
  | { screen: "home" }
  | { screen: "page"; page: string; search: URLSearchParams }
  | { screen: "list"; resource: string; search: URLSearchParams }
  | { screen: "create"; resource: string }
  | { screen: "detail"; resource: string; id: string }

function customPagePath(page: CustomPage): string {
  const cleaned = page.path?.replace(/^\/+|\/+$/g, "")
  return cleaned ? `/${cleaned}` : `/p/${encodeURIComponent(page.id)}`
}

/** Parses [pathname] and [search] under [base], such as `/admin/r/items/42` under `/admin`. */
export function parseRoute(base: string, pathname: string, search = "", customPages: readonly CustomPage[] = []): Route {
  const rest = pathname.startsWith(base) ? pathname.slice(base.length) : pathname
  const parts = rest.split("/").filter(Boolean).map(decodeURIComponent)
  const normalizedRest = parts.length > 0 ? `/${parts.join("/")}` : "/"
  const matchedPage = customPages.find((page) => customPagePath(page) === normalizedRest)
  if (matchedPage) return { screen: "page", page: matchedPage.id, search: new URLSearchParams(search) }
  if (parts[0] === "p" && parts[1] && parts.length === 2) {
    return { screen: "page", page: parts[1], search: new URLSearchParams(search) }
  }
  if (parts[0] !== "r" || !parts[1]) return { screen: "home" }
  if (parts.length === 2) return { screen: "list", resource: parts[1], search: new URLSearchParams(search) }
  if (parts[2] === "new" && parts.length === 3) return { screen: "create", resource: parts[1] }
  return { screen: "detail", resource: parts[1], id: parts.slice(2).join("/") }
}

/** The URL of a screen, under [base]. */
export function href(base: string, route: Route, customPages: readonly CustomPage[] = []): string {
  switch (route.screen) {
    case "home":
      return `${base}/`
    case "page": {
      const matched = customPages.find((page) => page.id === route.page)
      const subpath = matched ? customPagePath(matched) : `/p/${encodeURIComponent(route.page)}`
      const query = route.search.toString()
      return `${base}${subpath}${query ? `?${query}` : ""}`
    }
    case "list": {
      const query = route.search.toString()
      return `${base}/r/${encodeURIComponent(route.resource)}${query ? `?${query}` : ""}`
    }
    case "create":
      return `${base}/r/${encodeURIComponent(route.resource)}/new`
    case "detail":
      return `${base}/r/${encodeURIComponent(route.resource)}/${encodeURIComponent(route.id)}`
  }
}

interface RouterValue {
  base: string
  route: Route
  customPages: readonly CustomPage[]
  navigate: (route: Route, options?: { replace?: boolean }) => void
}

const RouterContext = createContext<RouterValue | null>(null)

const emptyPages: readonly CustomPage[] = []

/** Keeps the route in step with the browser's history under [base]. */
export function Router({
  base,
  customPages = emptyPages,
  children,
}: {
  base: string
  customPages?: readonly CustomPage[]
  children: ReactNode
}) {
  const read = useCallback(() => parseRoute(base, location.pathname, location.search, customPages), [base, customPages])
  const [route, setRoute] = useState<Route>(read)
  useEffect(() => {
    const onPop = () => setRoute(read())
    addEventListener("popstate", onPop)
    return () => removeEventListener("popstate", onPop)
  }, [read])
  const navigate = useCallback((next: Route, options?: { replace?: boolean }) => {
    const url = href(base, next, customPages)
    if (options?.replace) history.replaceState(null, "", url)
    else history.pushState(null, "", url)
    setRoute(next)
    if (!options?.replace) scrollTo?.(0, 0)
  }, [base, customPages])
  const value = useMemo(() => ({ base, route, customPages, navigate }), [base, route, customPages, navigate])
  return <RouterContext.Provider value={value}>{children}</RouterContext.Provider>
}

export function useRouter(): RouterValue {
  const value = useContext(RouterContext)
  if (!value) throw new Error("useRouter needs a <Router>.")
  return value
}

/** A link to a screen that navigates without reloading, and still opens in a new tab on ctrl- or middle-click. */
export function Link({ to, ...props }: { to: Route } & Omit<AnchorHTMLAttributes<HTMLAnchorElement>, "href">) {
  const { base, customPages, navigate } = useRouter()
  const onClick = (event: MouseEvent<HTMLAnchorElement>) => {
    props.onClick?.(event)
    if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
    event.preventDefault()
    navigate(to)
  }
  return <a {...props} href={href(base, to, customPages)} onClick={onClick} />
}
