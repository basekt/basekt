package io.github.basekt.admin.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The panel's prebuilt files in the jar, under [ROOT], and the entry Vite's manifest names. */
internal object PanelAssets {
    const val ROOT = "basekt-admin-ui"

    /** The entry's script and stylesheets, as paths relative to [ROOT], such as `assets/main-1a2b3c.js`. */
    class Entry(val script: String, val css: List<String>)

    @Serializable
    private class ManifestEntry(val file: String, val isEntry: Boolean = false, val css: List<String> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }

    /** Reads the entry from Vite's manifest under [root], failing with a clear message if missing. */
    fun load(classLoader: ClassLoader, root: String = ROOT): Entry {
        val text = classLoader.getResource("$root/.vite/manifest.json")?.readText()
            ?: error("The admin panel's files are missing from the classpath ($root/.vite/manifest.json). The jar was built without its web build.")
        val manifest = json.decodeFromString<Map<String, ManifestEntry>>(text)
        val entry = manifest.values.singleOrNull { it.isEntry }
            ?: error("The admin panel's manifest names ${manifest.values.count { it.isEntry }} entries; it should name one.")
        return Entry(entry.file, entry.css)
    }
}
