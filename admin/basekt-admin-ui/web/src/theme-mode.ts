import { useCallback, useEffect, useState } from "react"
import type { ThemeMode } from "@/config"

const key = "basekt-admin-ui:mode"

/** The mode the viewer chose last, if any. */
export function storedMode(): ThemeMode | null {
  try {
    const value = localStorage.getItem(key)
    return value === "light" || value === "dark" || value === "system" ? value : null
  } catch {
    return null
  }
}

/** Puts or removes the `dark` class on the document, which switches theme.css's `.dark` variables. */
export function applyMode(mode: ThemeMode, doc: Document = document): void {
  const dark = mode === "dark" || (mode === "system" && matchMedia("(prefers-color-scheme: dark)").matches)
  doc.documentElement.classList.toggle("dark", dark)
  doc.documentElement.style.colorScheme = dark ? "dark" : "light"
}

/** The current mode and a setter that applies and remembers it. Follows the system while the mode is `system`. */
export function useThemeMode(defaultMode: ThemeMode): [ThemeMode, (mode: ThemeMode) => void] {
  const [mode, setMode] = useState<ThemeMode>(() => storedMode() ?? defaultMode)
  useEffect(() => {
    applyMode(mode)
    if (mode !== "system") return
    const query = matchMedia("(prefers-color-scheme: dark)")
    const follow = () => applyMode("system")
    query.addEventListener("change", follow)
    return () => query.removeEventListener("change", follow)
  }, [mode])
  const choose = useCallback((next: ThemeMode) => {
    try {
      localStorage.setItem(key, next)
    } catch {
      // Private windows may refuse storage; the choice then lasts until reload.
    }
    setMode(next)
  }, [])
  return [mode, choose]
}
