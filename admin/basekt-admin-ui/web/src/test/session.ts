import type { Clock } from "@/auth"

/** A clock the test moves by hand. [advance] runs the timers that fall due, and lets their promises settle. */
export class ManualClock implements Clock {
  private time = 0
  private next = 1
  private readonly timers = new Map<number, { at: number; run: () => void }>()

  now(): number {
    return this.time
  }

  setTimeout(run: () => void, ms: number): unknown {
    const id = this.next++
    this.timers.set(id, { at: this.time + ms, run })
    return id
  }

  clearTimeout(handle: unknown): void {
    this.timers.delete(handle as number)
  }

  /** How many timers wait. */
  pending(): number {
    return this.timers.size
  }

  async advance(ms: number): Promise<void> {
    const end = this.time + ms
    for (;;) {
      const due = [...this.timers.entries()].filter(([, t]) => t.at <= end).sort((a, b) => a[1].at - b[1].at)[0]
      if (!due) break
      this.timers.delete(due[0])
      this.time = Math.max(this.time, due[1].at)
      due[1].run()
      await settle()
    }
    this.time = end
    await settle()
  }
}

async function settle(): Promise<void> {
  for (let i = 0; i < 10; i++) await new Promise((resolve) => setTimeout(resolve, 0))
}

/** A Storage kept in a map, standing for the tab's sessionStorage. */
export function memoryStorage(): Storage {
  const items = new Map<string, string>()
  return {
    get length() {
      return items.size
    },
    clear: () => items.clear(),
    getItem: (key) => items.get(key) ?? null,
    key: (index) => [...items.keys()][index] ?? null,
    removeItem: (key) => void items.delete(key),
    setItem: (key, value) => void items.set(key, value),
  }
}
