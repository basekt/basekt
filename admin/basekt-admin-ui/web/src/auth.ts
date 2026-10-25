import { useCallback, useEffect, useRef, useState } from "react"

const key = "basekt-admin-ui:session"

/** Where a session's token came from, which decides who may refresh it. */
export type SessionSource = "token" | "login" | "provider"

/**
 * A signed-in viewer's credentials: the bearer token the panel sends, and what it needs to renew it before
 * [expiresAt]. A pasted token has neither, and is used until the API refuses it.
 */
export interface Session {
  /** The bearer token. */
  token: string
  source: SessionSource
  /** The host's refresh token, kept only when the login route has a refresh route to send it to. */
  refreshToken: string | null
  /** When the token expires, in milliseconds since the epoch, or null when the panel can't tell. */
  expiresAt: number | null
}

/** A session from a pasted token. Its expiry is read from the token when it is a JWT. */
export function pastedSession(token: string, now: number = Date.now()): Session {
  return { token, source: "token", refreshToken: null, expiresAt: jwtExpiry(token, now) }
}

/**
 * The expiry of [token] in milliseconds since the epoch, from its `exp` claim when it is a JWT, or null otherwise.
 * Nothing is verified: the panel only uses it to know when to refresh.
 */
export function jwtExpiry(token: string, now: number = Date.now()): number | null {
  const parts = token.split(".")
  if (parts.length !== 3) return null
  try {
    const payload = JSON.parse(atob(parts[1].replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(parts[1].length / 4) * 4, "="))) as { exp?: unknown }
    return typeof payload.exp === "number" && payload.exp * 1000 > now - 365 * DAY ? payload.exp * 1000 : null
  } catch {
    return null
  }
}

/**
 * Where a signed-in viewer's session lives: this tab's sessionStorage when `remember` is on, so a reload keeps it,
 * and memory only otherwise. It never goes to localStorage, so it ends with the tab. A refresh keeps the choice.
 */
export class TokenStore {
  private memory: Session | null
  private remember: boolean

  constructor(private readonly storage: Storage | null = safeSessionStorage()) {
    this.memory = read(storage)
    this.remember = this.memory != null
  }

  /** The bearer token to send, if any. */
  get(): string | null {
    return this.memory?.token ?? null
  }

  /** The whole session, if any. */
  session(): Session | null {
    return this.memory
  }

  /** Signs in with [session], or with a pasted token. */
  set(session: Session | string, remember: boolean): void {
    this.memory = typeof session === "string" ? pastedSession(session) : session
    this.remember = remember
    write(this.storage, remember ? this.memory : null)
  }

  /** Replaces the session after a refresh, kept where the first one was. */
  update(session: Session): void {
    this.set(session, this.remember)
  }

  clear(): void {
    this.memory = null
    write(this.storage, null)
  }
}

/** The timers a [SessionKeeper] uses; tests pass their own. */
export interface Clock {
  now(): number
  setTimeout(run: () => void, ms: number): unknown
  clearTimeout(handle: unknown): void
}

const realClock: Clock = {
  now: () => Date.now(),
  setTimeout: (run, ms) => globalThis.setTimeout(run, ms),
  clearTimeout: (handle) => globalThis.clearTimeout(handle as ReturnType<typeof setTimeout>),
}

/** Renews a session, or fails. A [RefreshUnavailable] failure is tried again while the token is still valid. */
export type Refresh = (session: Session) => Promise<Session>

/** Thrown by a [Refresh] for a failure that may pass, such as the network dropping. */
export class RefreshUnavailable extends Error {}

/**
 * Refreshes the store's session before it expires: a minute early, or halfway through a shorter life. When a refresh
 * fails, it clears the session and calls `ended`, so the panel signs the viewer out cleanly rather than failing its
 * next call. A network failure is retried every few seconds while the token is still valid.
 */
export class SessionKeeper {
  private handle: unknown = null
  private stopped = false

  constructor(
    private readonly store: TokenStore,
    /** The refresh for a session, or null when nobody can refresh it, as for a pasted token. */
    private readonly refreshFor: (session: Session) => Refresh | null,
    private readonly changed: (session: Session) => void,
    private readonly ended: (reason: string, session: Session) => void,
    private readonly clock: Clock = realClock,
  ) {}

  /** Plans the next refresh for the store's current session; call it after every sign-in. */
  watch(): void {
    this.cancel()
    this.stopped = false
    const session = this.store.session()
    if (!session || session.expiresAt == null || !this.refreshFor(session)) return
    const left = session.expiresAt - this.clock.now()
    const early = Math.min(MARGIN, Math.max(0, left / 2))
    this.schedule(Math.max(0, left - early))
  }

  /** Whether the store's session has expired and is waiting for its refresh. */
  expired(): boolean {
    const session = this.store.session()
    return session?.expiresAt != null && session.expiresAt <= this.clock.now() && this.refreshFor(session) != null
  }

  /** Stops refreshing, as on sign-out or unmount. */
  stop(): void {
    this.stopped = true
    this.cancel()
  }

  private schedule(ms: number): void {
    this.handle = this.clock.setTimeout(() => void this.run(), Math.min(ms, MAX_TIMER))
  }

  private cancel(): void {
    if (this.handle != null) this.clock.clearTimeout(this.handle)
    this.handle = null
  }

  private async run(): Promise<void> {
    this.handle = null
    const session = this.store.session()
    const refresh = session && this.refreshFor(session)
    if (!session || !refresh) return
    if (session.expiresAt != null && session.expiresAt - this.clock.now() > MARGIN + MAX_TIMER / 2) {
      this.watch() // A timer capped at MAX_TIMER woke up early.
      return
    }
    let next: Session
    try {
      next = await refresh(session)
    } catch (error) {
      if (this.stopped || this.store.session() !== session) return
      const left = (session.expiresAt ?? 0) - this.clock.now()
      if (error instanceof RefreshUnavailable && left > RETRY) {
        this.schedule(RETRY)
        return
      }
      this.store.clear()
      this.ended("Your session ended. Sign in again.", session)
      return
    }
    if (this.stopped || this.store.session() !== session) return
    this.store.update(next)
    this.changed(next)
    this.watch()
  }
}

const DAY = 24 * 60 * 60 * 1000
const MARGIN = 60_000
const RETRY = 5_000
// Browsers run a longer timeout at once.
const MAX_TIMER = 2 ** 31 - 1

/**
 * The store's session as React state, refreshed by a [SessionKeeper] while the panel is open. Returns the session,
 * a sign-in, a sign-out (which also stops refreshing and runs [onSignOut]), and the reason the last session ended.
 */
export function useSession(
  store: TokenStore,
  refreshFor: (session: Session) => Refresh | null,
  onSignOut: (session: Session) => void = () => {},
  clock?: Clock,
): {
  session: Session | null
  /** True while an expired session from a reload waits for its first refresh. */
  renewing: boolean
  signIn: (session: Session, remember: boolean) => void
  signOut: () => void
  ended: string | null
} {
  const [session, setSession] = useState(() => store.session())
  const [ended, setEnded] = useState<string | null>(null)
  const refreshRef = useRef(refreshFor)
  refreshRef.current = refreshFor
  const signOutRef = useRef(onSignOut)
  signOutRef.current = onSignOut
  const [keeper] = useState(
    () =>
      new SessionKeeper(
        store,
        (s) => refreshRef.current(s),
        (next) => setSession(next),
        (reason, last) => {
          setSession(null)
          setEnded(reason)
          signOutRef.current(last)
        },
        clock,
      ),
  )
  const [initial] = useState(session)
  const [expiredAtStart] = useState(() => keeper.expired())
  const renewing = expiredAtStart && session === initial && ended === null

  useEffect(() => {
    keeper.watch()
    return () => keeper.stop()
  }, [keeper])

  const signIn = useCallback(
    (value: Session, remember: boolean) => {
      store.set(value, remember)
      setSession(value)
      setEnded(null)
      keeper.watch()
    },
    [store, keeper],
  )
  const signOut = useCallback(() => {
    const last = store.session()
    keeper.stop()
    store.clear()
    setSession(null)
    if (last) signOutRef.current(last)
  }, [store, keeper])
  return { session, renewing, signIn, signOut, ended }
}

function safeSessionStorage(): Storage | null {
  try {
    return window.sessionStorage
  } catch {
    return null
  }
}

function read(storage: Storage | null): Session | null {
  try {
    const text = storage?.getItem(key)
    if (!text) return null
    const value = JSON.parse(text) as Partial<Session>
    if (typeof value.token !== "string" || !value.token) return null
    const source: SessionSource = value.source === "login" || value.source === "provider" ? value.source : "token"
    return {
      token: value.token,
      source,
      refreshToken: typeof value.refreshToken === "string" ? value.refreshToken : null,
      expiresAt: typeof value.expiresAt === "number" ? value.expiresAt : null,
    }
  } catch {
    return null
  }
}

function write(storage: Storage | null, session: Session | null): void {
  try {
    if (session == null) storage?.removeItem(key)
    else storage?.setItem(key, JSON.stringify(session))
  } catch {
    // Storage may be refused; the session then lives in memory only.
  }
}
