import { Moon, Sun } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import type { PanelConfig } from "@/config"
import { useThemeMode } from "@/theme-mode"

/** The panel. For now a placeholder that proves the build, the theme and the config reach the browser. */
export function App({ config }: { config: PanelConfig }) {
  const [mode, setMode] = useThemeMode(config.defaultMode)
  const dark = document.documentElement.classList.contains("dark")
  return (
    <main className="flex min-h-svh items-center justify-center p-6">
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle>{config.brand.name} admin</CardTitle>
          <CardDescription>The admin panel is installed. It reads the admin API at {config.apiPath}.</CardDescription>
        </CardHeader>
        <CardContent className="flex items-center gap-2">
          <Button onClick={() => setMode(dark ? "light" : "dark")} variant="outline" aria-label="Toggle dark mode">
            {dark ? <Sun /> : <Moon />}
            {mode === "system" ? "System" : mode === "dark" ? "Dark" : "Light"}
          </Button>
        </CardContent>
      </Card>
    </main>
  )
}
