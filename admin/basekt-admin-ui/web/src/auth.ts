import { useCallback, useState } from "react"

const key = "basekt-admin-ui:token"

/**
 * Where a signed-in viewer's bearer token lives: this tab's sessionStorage when [remember] is on, so a reload keeps
 * it, and memory only otherwise. It never goes to localStorage, so it ends with the tab.
 */
export class TokenStore {
  private memory: string | null

  constructor(private readonly storage: Storage | null = safeSessionStorage()) {
    this.memory = read(storage)
  }

  get(): string | null {
    return this.memory
  }

  set(token: string, remember: boolean): void {
    this.memory = token
    if (remember) write(this.storage, token)
    else write(this.storage, null)
  }

  clear(): void {
    this.memory = null
    write(this.storage, null)
  }
}

/** The store's token as React state, so signing in and out re-renders. */
export function useToken(store: TokenStore): [string | null, (token: string, remember: boolean) => void, () => void] {
  const [token, setToken] = useState(() => store.get())
  const signIn = useCallback((value: string, remember: boolean) => {
    store.set(value, remember)
    setToken(value)
  }, [store])
  const signOut = useCallback(() => {
    store.clear()
    setToken(null)
  }, [store])
  return [token, signIn, signOut]
}

function safeSessionStorage(): Storage | null {
  try {
    return window.sessionStorage
  } catch {
    return null
  }
}

function read(storage: Storage | null): string | null {
  try {
    return storage?.getItem(key) ?? null
  } catch {
    return null
  }
}

function write(storage: Storage | null, token: string | null): void {
  try {
    if (token == null) storage?.removeItem(key)
    else storage?.setItem(key, token)
  } catch {
    // Storage may be refused; the token then lives in memory only.
  }
}
