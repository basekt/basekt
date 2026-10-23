/// <reference types="vitest/config" />
import { fileURLToPath } from "node:url"
import react from "@vitejs/plugin-react"
import tailwindcss from "@tailwindcss/vite"
import { defineConfig } from "vite"

const here = (path: string) => fileURLToPath(new URL(path, import.meta.url))

// The panel is built into dist/ and copied into the jar by Gradle. The Ktor plugin serves its own shell page and
// finds the entry's files through dist/.vite/manifest.json, so index.html is only for `npm run dev`.
//
// The wire types come straight from basekt-admin-api's generated file: a change to the admin API's JSON that the
// panel doesn't handle fails this build, not the panel at runtime.
// `npm run dev` serves the panel at /admin/ and sends the API and theme to a server running the plugin, such as the
// sample shop: http://localhost:8080 unless BASEKT_SERVER says otherwise.
const server = process.env.BASEKT_SERVER ?? "http://localhost:8080"

export default defineConfig(({ command }) => ({
  plugins: [react(), tailwindcss()],
  base: command === "build" ? "./" : "/admin/",
  resolve: {
    alias: {
      "@wire": here("../../basekt-admin-api/typescript/basekt-admin-api.ts"),
      "@": here("./src"),
    },
  },
  build: {
    manifest: true,
    rollupOptions: { input: here("./src/main.tsx") },
  },
  server: {
    proxy: { "/admin/api": server, "/admin/theme.css": server },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
  },
}))
