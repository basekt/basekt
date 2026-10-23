import { StrictMode } from "react"
import { createRoot } from "react-dom/client"
import { App } from "@/App"
import { readConfig } from "@/config"
import { applyMode, storedMode } from "@/theme-mode"
import "@/index.css"

const config = readConfig()
applyMode(storedMode() ?? config.defaultMode)

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App config={config} />
  </StrictMode>,
)
