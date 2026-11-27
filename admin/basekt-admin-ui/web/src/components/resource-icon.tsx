import { useEffect, useState } from "react"
import { Table2, type LucideIcon } from "lucide-react"

type Lookup = (name: string) => LucideIcon | undefined
let loading: Promise<Lookup> | undefined
let loaded: Lookup | undefined
const loadIcons = () => (loading ??= import("@/lib/lucide-icons").then((m) => (loaded = m.lucideIcon)))
const warned = new Set<string>()

/**
 * A resource's icon, from its `adminView { icon(...) }`: any Lucide icon by its name on lucide.dev. The icons are a
 * chunk of their own, fetched the first time one is shown. A resource without one, or naming one this Lucide doesn't
 * have, shows a table; the console names the unknown one once.
 */
export function ResourceIcon({ name, className }: { name: string | null | undefined; className?: string }) {
  const [lookup, setLookup] = useState(() => loaded)
  useEffect(() => {
    if (name && !lookup) loadIcons().then((found) => setLookup(() => found), () => setLookup(() => () => undefined))
  }, [name, lookup])
  // Until the icons arrive, an empty box of the icon's size keeps the label from moving.
  if (name && !lookup) return <span aria-hidden className={className} />
  const Icon = name ? lookup?.(name) : undefined
  if (name && !Icon && !warned.has(name)) {
    warned.add(name)
    console.warn(`BaseKt admin: Lucide has no icon named '${name}', so a table is shown. See https://lucide.dev/icons for the names.`)
  }
  return Icon ? <Icon className={className} aria-hidden /> : <Table2 className={className} aria-hidden />
}
