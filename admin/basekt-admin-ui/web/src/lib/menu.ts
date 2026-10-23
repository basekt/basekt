import type { AdminResource } from "@wire"

/** One heading of the menu and the resources under it. */
export interface MenuSection {
  group: string | null
  resources: AdminResource[]
}

/**
 * Splits the resources into the menu's sections: those without a group first, then each group in the order its first
 * resource is declared. Resources keep their declared order inside a section.
 */
export function menuSections(resources: AdminResource[]): MenuSection[] {
  const sections: MenuSection[] = []
  for (const resource of resources) {
    const group = resource.group ?? null
    const section = sections.find((s) => s.group === group)
    if (section) section.resources.push(resource)
    else sections.push({ group, resources: [resource] })
  }
  return sections.sort((a, b) => Number(a.group !== null) - Number(b.group !== null))
}
