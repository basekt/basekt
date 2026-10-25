import type { AdminField, AdminResource, FieldType, JsonValue } from "@wire"

/** The record id every resource has, which the schema's field list leaves out. */
export const ID = "id"

/** A label for a field or resource name: `categoryId` → "Category", `placedAt` → "Placed at", `order_items` → "Order items". */
export function humanize(name: string): string {
  const words = name
    .replace(/Id$/, "")
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .replace(/[_-]+/g, " ")
    .trim()
    .toLowerCase()
  return words ? words[0].toUpperCase() + words.slice(1) : name
}

/** A record's id as a string, whatever JSON type it came as. */
export function recordId(record: { [key: string]: JsonValue }): string {
  return String(record[ID] ?? "")
}

/** A short text for a value in a table cell or a read-only view. */
export function formatValue(field: AdminField | undefined, value: JsonValue | undefined): string {
  if (value === null || value === undefined || value === "") return "—"
  switch (field?.type) {
    case "bool":
      return value ? "Yes" : "No"
    case "instant": {
      const date = new Date(String(value))
      return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString()
    }
    default:
      return typeof value === "object" ? JSON.stringify(value) : String(value)
  }
}

/** A filter operator of the admin API's `field[op]=value` syntax. `eq` is written `field=value`. */
export type FilterOp = "eq" | "ne" | "gt" | "gte" | "lt" | "lte" | "contains" | "null"

/** What each operator is called in the filter menu. */
export const opLabels: Record<FilterOp, string> = {
  eq: "is",
  ne: "is not",
  gt: ">",
  gte: "≥",
  lt: "<",
  lte: "≤",
  contains: "contains",
  null: "is empty",
}

/** The operators that make sense for a field type. */
export function opsFor(type: FieldType): FilterOp[] {
  switch (type) {
    case "string":
      return ["contains", "eq", "ne", "null"]
    case "int":
    case "long":
    case "decimal":
    case "instant":
      return ["eq", "ne", "gt", "gte", "lt", "lte", "null"]
    case "bool":
    case "enum":
    case "reference":
    case "file":
      return ["eq", "ne", "null"]
    default:
      return ["eq", "ne", "null"]
  }
}

/**
 * The fields a list may be filtered on, each with the operators it takes: every one that suits its type, or only
 * those the resource lists in `filters`, as a read-only resource served by the application's own code does.
 */
export function filterChoices(resource: AdminResource): { field: AdminField; ops: FilterOp[] }[] {
  return resource.fields.flatMap((field) => {
    const suited = opsFor(field.type)
    const declared = resource.filters?.find((f) => f.field === field.name)?.operators
    const ops = resource.filters ? suited.filter((op) => declared?.some((d) => d === op)) : suited
    return ops.length > 0 ? [{ field, ops }] : []
  })
}

/** One active filter. */
export interface Filter {
  field: string
  op: FilterOp
  value: string
}

const filterKey = /^([A-Za-z0-9_]+)(?:\[([a-z]+)\])?$/

/** The filters among a list URL's parameters: every `field` or `field[op]` that names a filterable field. */
export function filtersOf(resource: AdminResource, params: URLSearchParams): Filter[] {
  const names = new Set([ID, ...resource.fields.map((f) => f.name)])
  const filters: Filter[] = []
  params.forEach((value, key) => {
    const match = filterKey.exec(key)
    if (!match || !names.has(match[1])) return
    const op = (match[2] ?? "eq") as FilterOp
    if (op in opLabels) filters.push({ field: match[1], op, value })
  })
  return filters
}

/** The URL parameter a filter is written as. */
export function filterParam(filter: Filter): [string, string] {
  return [filter.op === "eq" ? filter.field : `${filter.field}[${filter.op}]`, filter.op === "null" ? "true" : filter.value]
}
