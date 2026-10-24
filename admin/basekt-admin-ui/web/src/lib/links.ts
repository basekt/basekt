import type { AdminLink, JsonObject } from "@wire"

/** A link of a record, with its placeholders filled in. */
export interface RecordLink {
  label: string
  href: string
}

/**
 * Whether [href] may be followed: a path on this server, or an http or https address. The server checks the same when
 * it starts; this keeps a link from ever being a `javascript:` address even if a record's value were odd.
 */
export function isSafeHref(href: string): boolean {
  return (href.startsWith("/") && !href.startsWith("//")) || href.startsWith("https://") || href.startsWith("http://")
}

/**
 * Fills a link's `{id}` and `{field}` placeholders from a record, percent-encoded so a value can't change the shape of
 * the address. Returns null if the record has no value for one of them.
 */
export function fillLink(template: string, record: JsonObject): string | null {
  let missing = false
  const href = template.replace(/\{([^{}]*)\}/g, (_match, name: string) => {
    const value = record[name]
    if (value === null || value === undefined || value === "" || typeof value === "object") {
      missing = true
      return ""
    }
    return encodeURIComponent(String(value))
  })
  return missing ? null : href
}

/** The links of a resource that [record] has every value for, ready to follow. */
export function recordLinks(links: AdminLink[], record: JsonObject): RecordLink[] {
  return links.flatMap((link) => {
    const href = fillLink(link.url, record)
    return href !== null && isSafeHref(href) ? [{ label: link.label, href }] : []
  })
}
