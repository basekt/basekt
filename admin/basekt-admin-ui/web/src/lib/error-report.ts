import { ApiFailure, recordOf, type FailureRecord } from "@/api/client"

/**
 * What a viewer pastes when they report [error]: when and where it happened, the call and its answer, the request id
 * the server's logs name, and the browser. No token or record data goes in it.
 */
export function errorReport(error: unknown, now: Date = new Date()): string {
  const failure = error instanceof ApiFailure ? recordOf(error) : null
  const lines = [
    "Admin panel error",
    `When: ${failure?.at ?? now.toISOString()}`,
    `Screen: ${location.pathname}${location.search}`,
  ]
  if (failure) {
    if (failure.method && failure.path) lines.push(`Call: ${failure.method} ${failure.path}`)
    lines.push(`Answer: ${describeAnswer(failure)}`)
  }
  lines.push(`Message: ${error instanceof Error ? error.message : String(error)}`)
  lines.push(`Request id: ${failure?.requestId ?? "none (the server didn't answer)"}`)
  lines.push(`Browser: ${navigator.userAgent}`)
  return lines.join("\n")
}

/** The downloadable log of [failures], newest first, with where and when it was taken. */
export function errorLog(failures: FailureRecord[], now: Date = new Date()): string {
  const log = { takenAt: now.toISOString(), screen: `${location.pathname}${location.search}`, browser: navigator.userAgent, failures }
  return JSON.stringify(log, null, 2)
}

/** The name of a downloaded log taken at [now], such as `admin-errors-2026-10-24T08-30-00Z.json`. */
export function errorLogName(now: Date = new Date()): string {
  return `admin-errors-${now.toISOString().replace(/\.\d+Z$/, "Z").replace(/:/g, "-")}.json`
}

function describeAnswer(failure: FailureRecord): string {
  if (failure.status === 0) return "none: the server couldn't be reached"
  return failure.error ? `${failure.status} ${failure.error}` : String(failure.status)
}
