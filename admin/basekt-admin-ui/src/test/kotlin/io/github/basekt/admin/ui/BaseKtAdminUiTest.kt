package io.github.basekt.admin.ui

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BaseKtAdminUiTest {
    private val configScript = Regex("""<script type="application/json" id="basekt-admin-config">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    private val moduleScript = Regex("""<script type="module" src="([^"]+)"""")

    private suspend fun ApplicationTestBuilder.shellConfig(url: String) =
        Json.parseToJsonElement(configScript.find(client.get(url).bodyAsText())!!.groupValues[1]).jsonObject

    // A default install, as a shop might use it next to BaseKtAdmin's default /admin/api.
    @Test
    fun `serves the shell with its config, theme and assets`() = testApplication {
        application { install(BaseKtAdminUi) }
        val response = client.get("/admin")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Text.Html, response.contentType()?.withoutParameters())
        val html = response.bodyAsText()
        assertContains(html, """<link href="/admin/theme.css" rel="stylesheet">""")
        assertContains(html, "<title>BaseKt admin</title>")

        val config = shellConfig("/admin")
        assertEquals("/admin", config["path"]!!.jsonPrimitive.content)
        assertEquals("/admin/api", config["apiPath"]!!.jsonPrimitive.content)
        assertEquals("system", config["defaultMode"]!!.jsonPrimitive.content)
        assertEquals("/files", config["filesPath"]!!.jsonPrimitive.content)

        val script = assertNotNull(moduleScript.find(html)).groupValues[1]
        val asset = client.get(script)
        assertEquals(HttpStatusCode.OK, asset.status)
        assertContains(asset.headers[HttpHeaders.CacheControl].orEmpty(), "max-age=31536000")
        assertTrue(asset.bodyAsText().isNotEmpty())
    }

    // A back office under its own paths, branded and themed from Kotlin, starting dark.
    @Test
    fun `honours the paths, brand, mode and theme it is given`() = testApplication {
        application {
            install(BaseKtAdminUi) {
                path = "/back-office/"
                apiPath = "/api/back-office"
                filesPath = "/uploads/"
                defaultMode = ThemeMode.Dark
                brand {
                    name = "Acme <Ops>"
                    logo = "/static/logo.svg"
                }
                theme {
                    radius = Radius.rem(0.25)
                    light { this[Token.Primary] = Hex("#1E40AF") }
                    dark { this[Token.Primary] = Oklch(0.8, 0.1, 255.0) }
                }
            }
        }
        val html = client.get("/back-office").bodyAsText()
        assertContains(html, """<html lang="en" class="dark">""")
        assertContains(html, "<title>Acme &lt;Ops&gt; admin</title>")
        val config = shellConfig("/back-office")
        assertEquals("/back-office", config["path"]!!.jsonPrimitive.content)
        assertEquals("/api/back-office", config["apiPath"]!!.jsonPrimitive.content)
        assertEquals("/uploads", config["filesPath"]!!.jsonPrimitive.content)
        assertEquals("Acme <Ops>", config["brand"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("/static/logo.svg", config["brand"]!!.jsonObject["logo"]!!.jsonPrimitive.content)

        val css = client.get("/back-office/theme.css")
        assertEquals(ContentType.Text.CSS, css.contentType()?.withoutParameters())
        val theme = Theme.fromCss(css.bodyAsText())
        assertEquals(Radius.rem(0.25), theme.radius)
        assertEquals(Hex("#1e40af"), theme.light[Token.Primary])
        assertEquals(Oklch(0.8, 0.1, 255.0), theme.dark[Token.Primary])
        // What the theme leaves out keeps the defaults.
        assertEquals(Theme.Default.light[Token.Border], theme.light[Token.Border])
        assertEquals(Theme.Default.dark.keys, theme.dark.keys)
    }

    @Test
    fun `settings come from the application config`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "basekt.adminUi.path" to "/console",
                "basekt.adminUi.apiPath" to "/console-api",
                "basekt.adminUi.defaultMode" to "light",
                "basekt.adminUi.brand.name" to "Tracker",
            )
        }
        application { install(BaseKtAdminUi) }
        val config = shellConfig("/console")
        assertEquals("/console-api", config["apiPath"]!!.jsonPrimitive.content)
        assertEquals("light", config["defaultMode"]!!.jsonPrimitive.content)
        assertEquals("Tracker", config["brand"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a login route tells the panel to ask for a name and a password`() = testApplication {
        environment { config = MapApplicationConfig("basekt.adminUi.login.path" to "/auth/login") }
        application { install(BaseKtAdminUi) { login { usernameField = "username"; usernameLabel = "Staff id" } } }
        val login = shellConfig("/admin")["login"]!!.jsonObject
        assertEquals("/auth/login", login["path"]!!.jsonPrimitive.content)
        assertEquals("username", login["usernameField"]!!.jsonPrimitive.content)
        assertEquals("Staff id", login["usernameLabel"]!!.jsonPrimitive.content)
        assertEquals("password", login["passwordField"]!!.jsonPrimitive.content)
        assertEquals("token", login["tokenField"]!!.jsonPrimitive.content)
    }

    @Test
    fun `without a login route the panel asks for a token`() = testApplication {
        application { install(BaseKtAdminUi) }
        assertEquals(JsonNull, shellConfig("/admin")["login"])
    }

    @Test
    fun `a site link goes to the panel's top bar, and is checked`() = testApplication {
        application { install(BaseKtAdminUi) { siteLink("Open lessons", "/") } }
        val link = shellConfig("/admin")["siteLink"]!!.jsonObject
        assertEquals("Open lessons", link["label"]!!.jsonPrimitive.content)
        assertEquals("/", link["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the site link can come from configuration, and is absent otherwise`() = testApplication {
        environment { config = MapApplicationConfig("basekt.adminUi.siteLink.url" to "https://example.test/", "basekt.adminUi.siteLink.label" to "Public site") }
        application { install(BaseKtAdminUi) }
        val link = shellConfig("/admin")["siteLink"]!!.jsonObject
        assertEquals("Public site", link["label"]!!.jsonPrimitive.content)
        assertEquals("https://example.test/", link["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no site link means none in the config`() = testApplication {
        application { install(BaseKtAdminUi) }
        assertEquals(JsonNull, shellConfig("/admin")["siteLink"])
    }

    @Test
    fun `a site link to anything but a path or an http address is refused`() {
        for (bad in listOf("javascript:alert(1)", "//evil.test", "site", "/with space")) {
            val error = assertFailsWith<Throwable> {
                testApplication {
                    application { install(BaseKtAdminUi) { siteLink("Open", bad) } }
                    startApplication()
                }
            }
            assertTrue(generateSequence(error) { it.cause }.any { "site link must be a path" in it.message.orEmpty() }, bad)
        }
    }

    @Test
    fun `deep links get the shell, but the API and missing assets stay 404`() = testApplication {
        application {
            install(BaseKtAdminUi)
            routing { get("/admin/api/schema") { call.respondText("""{"version":1}""", ContentType.Application.Json) } }
        }
        for (link in listOf("/admin/", "/admin/r/products", "/admin/r/products/42/edit")) {
            val response = client.get(link)
            assertEquals(HttpStatusCode.OK, response.status, link)
            assertContains(response.bodyAsText(), "basekt-admin-config", message = link)
        }
        assertEquals("""{"version":1}""", client.get("/admin/api/schema").bodyAsText())
        val unknownApi = client.get("/admin/api/nothing-here")
        assertEquals(HttpStatusCode.NotFound, unknownApi.status)
        assertFalse(unknownApi.bodyAsText().contains("basekt-admin-config"))
        assertEquals(HttpStatusCode.NotFound, client.get("/admin/assets/missing.js").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/elsewhere").status)
    }

    @Test
    fun `refuses paths and logos it can't serve`() {
        for (bad in listOf<BaseKtAdminUiConfig.() -> Unit>(
            { path = "/" },
            { path = "/admin"; apiPath = "/admin/" },
            { brand { logo = "javascript:alert(1)" } },
            // A password goes only to this server.
            { login { path = "https://elsewhere.test/login" } },
            { login { path = "//elsewhere.test/login" } },
            { login { path = "/auth/login"; tokenField = " " } },
        )) {
            assertFailsWith<IllegalArgumentException> {
                testApplication {
                    application { install(BaseKtAdminUi, bad) }
                    client.get("/")
                }
            }
        }
    }
}
