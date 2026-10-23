package io.github.basekt.admin.ui

import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.head
import kotlinx.html.id
import kotlinx.html.lang
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.script
import kotlinx.html.title
import kotlinx.html.unsafe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the shell page tells the panel, as JSON in its `basekt-admin-config` script. The panel's `PanelConfig`
 * (web/src/config.ts) reads it; keep the two in step.
 */
@Serializable
internal data class ShellConfig(
    val path: String,
    val apiPath: String,
    val filesPath: String,
    val brand: ShellBrand,
    val defaultMode: ThemeMode,
)

@Serializable
internal data class ShellBrand(val name: String, val logo: String?)

private val json = Json { encodeDefaults = true }

/**
 * The page every panel URL answers with: the theme and the panel's stylesheet, the config as JSON, and the
 * panel's script, which builds the rest in the browser.
 */
internal suspend fun ApplicationCall.respondShellPage(config: ShellConfig, entry: PanelAssets.Entry) {
    // Inside a <script>, "</" could end the element early; JSON allows "<\/" for it.
    val configJson = json.encodeToString(ShellConfig.serializer(), config).replace("</", "<\\/")
    respondHtml {
        lang = "en"
        if (config.defaultMode == ThemeMode.Dark) attributes["class"] = "dark"
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            title(config.brand.name + " admin")
            link(rel = "stylesheet", href = "${config.path}/theme.css")
            for (css in entry.css) link(rel = "stylesheet", href = "${config.path}/$css")
            script(type = "application/json") {
                id = "basekt-admin-config"
                unsafe { +configJson }
            }
            script(type = "module", src = "${config.path}/${entry.script}") {}
        }
        body {
            div { id = "root" }
        }
    }
}
