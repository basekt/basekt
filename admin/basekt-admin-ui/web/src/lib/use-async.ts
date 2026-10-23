import { useCallback, useEffect, useState } from "react"

/** The state of a load: its value, or its error, while [loading] says whether one is in flight. */
export interface AsyncState<T> {
  data: T | undefined
  error: unknown
  loading: boolean
  reload: () => void
}

/**
 * Runs [load] when [deps] change, and again on `reload()`. A result that arrives after the deps changed is dropped,
 * so a slow answer never overwrites a newer one.
 */
export function useAsync<T>(load: () => Promise<T>, deps: unknown[]): AsyncState<T> {
  const [state, setState] = useState<{ data: T | undefined; error: unknown; loading: boolean }>({ data: undefined, error: undefined, loading: true })
  const [version, setVersion] = useState(0)
  useEffect(() => {
    let current = true
    setState((previous) => ({ ...previous, loading: true }))
    load().then(
      (data) => current && setState({ data, error: undefined, loading: false }),
      (error: unknown) => current && setState({ data: undefined, error, loading: false }),
    )
    return () => {
      current = false
    }
    // [load] is a fresh closure each render; [deps] say when it means something new.
  }, [...deps, version])
  const reload = useCallback(() => setVersion((v) => v + 1), [])
  return { ...state, reload }
}
