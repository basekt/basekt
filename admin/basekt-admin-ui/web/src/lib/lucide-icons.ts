import * as lucide from "lucide-react"
import type { LucideIcon } from "lucide-react"

// Every Lucide icon, under each of its component names, old ones included (`Building2` is now `BuildingComplex`).
// Imported only through import(), so it is a chunk of its own that the panel fetches once, the first time a resource
// names an icon.
const exports = lucide as unknown as Record<string, unknown>

/** `graduation-cap`, as lucide.dev names an icon, to `GraduationCap`, as lucide-react names its component. */
export const componentName = (name: string) => name.replace(/(^|-)([a-z0-9])/g, (_, __, c: string) => c.toUpperCase())

/** The icon lucide.dev names [name], or `undefined`. Only icons have a display name, so `icon` isn't Lucide's base `Icon`. */
export function lucideIcon(name: string): LucideIcon | undefined {
  const found = exports[componentName(name)]
  const isIcon = typeof found === "object" && found !== null && "displayName" in found && Boolean(found.displayName)
  return isIcon ? (found as LucideIcon) : undefined
}
