package io.github.basekt.admin

import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.schema
import io.github.basekt.server.BaseKt
import io.github.basekt.server.Identity
import io.github.basekt.server.IdentityResolver
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A made-up garden club: plants have a page on the club's site, and a hidden supplier note that no link may use. */
private val garden: Schema = schema {
    resource("plants") {
        string("name") { required() }
        string("slug")
        string("supplierNote") { hidden() }
        adminLinks {
            link("View on site", "/plants/{id}")
            link("Preview", "/preview?slug={slug}")
            link("Wiki", "https://wiki.example.test/{name}")
        }
        permissions { read("member"); write("member") }
    }
    resource("notes") {
        string("text")
        permissions { read("member") }
    }
}

class AdminLinksTest {
    private fun problems(build: io.github.basekt.schema.ResourceBuilder.() -> Unit) =
        schema { resource("plants") { string("name"); string("slug"); string("secret") { hidden() }; build() } }.adminLinkProblems()

    @Test
    fun `a resource's links are kept in its schema and reach the panel with the resource`() = testApplication {
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = garden
                identities = IdentityResolver { Identity("m1", setOf("member")) }
            }
            install(BaseKtAdmin)
        }
        val schemaJson = Json.parseToJsonElement(client.get("/admin/api/schema").bodyAsText()).jsonObject
        val resources = schemaJson["resources"]!!.jsonArray.associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }
        val links = resources.getValue("plants").jsonObject["links"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("View on site", "Preview", "Wiki"), links.map { it["label"]!!.jsonPrimitive.content })
        assertEquals("/plants/{id}", links.first()["url"]!!.jsonPrimitive.content)
        assertEquals(0, resources.getValue("notes").jsonObject["links"]!!.jsonArray.size)
        assertEquals(emptyList(), garden.adminLinkProblems())
    }

    @Test
    fun `links read back from the resource, and adminLink adds one`() {
        val plants = garden.resource("plants")!!
        assertEquals(3, plants.adminLinks.size)
        val single = schema { resource("things") { string("name"); adminLink("Open", "/things/{id}") } }
        assertEquals(listOf(AdminLink("Open", "/things/{id}")), single.resource("things")!!.adminLinks)
        assertEquals(emptyList(), garden.resource("notes")!!.adminLinks)
        assertEquals(garden, Schema.fromJson(garden.toJson()), "links survive the schema's JSON form")
        assertEquals(3, Schema.fromJson(garden.toJson()).resource("plants")!!.adminLinks.size)
    }

    @Test
    fun `an address must be a path or an http address`() {
        for (bad in listOf("javascript:alert(1)", "//evil.test/x", "plants/{id}", "mailto:a@b.test", "/has space", "data:text/html,x", "")) {
            assertTrue(problems { adminLink("Open", bad) }.any { "isn't a path starting with '/'" in it }, "'$bad' should be refused")
        }
        for (good in listOf("/plants/{id}", "/", "https://example.test/a?b={slug}", "http://localhost:8080/x")) {
            assertEquals(emptyList(), problems { adminLink("Open", good) }, "'$good' should be accepted")
        }
    }

    @Test
    fun `a placeholder must be the id or a field the panel may show`() {
        assertEquals(emptyList(), problems { adminLink("Open", "/p/{id}/{slug}") })
        assertTrue(problems { adminLink("Open", "/p/{missing}") }.single().contains("'{missing}', which isn't a field"))
        assertTrue(problems { adminLink("Open", "/p/{secret}") }.single().contains("a hidden field"))
        assertTrue(problems { adminLink("Open", "/p/{na me}") }.isNotEmpty())
        assertTrue(problems { adminLink("Open", "/p/{id") }.single().contains("unmatched brace"))
        assertTrue(problems { adminLink("Open", "/p/id}") }.single().contains("unmatched brace"))
    }

    @Test
    fun `a label must be short and not blank`() {
        assertTrue(problems { adminLink("", "/p") }.single().contains("label of 1 to 40"))
        assertTrue(problems { adminLink(" ", "/p") }.isNotEmpty())
        assertTrue(problems { adminLink("x".repeat(41), "/p") }.isNotEmpty())
        assertEquals(emptyList(), problems { adminLink("x".repeat(40), "/p") })
    }

    @Test
    fun `installing the admin API with a bad link fails and says which`() {
        val broken = schema { resource("plants") { string("name"); adminLink("Open", "/p/{nope}") } }
        val error = assertFailsWith<Throwable> {
            testApplication {
                application {
                    install(BaseKt) { dataSource = InMemoryDataSource(); schema = broken }
                    install(BaseKtAdmin)
                }
                startApplication()
            }
        }
        assertTrue(generateSequence(error) { it.cause }.any { "admin links are not valid" in it.message.orEmpty() && "'{nope}'" in it.message.orEmpty() })
        assertEquals(Resource.ID, "id", "the id placeholder is the schema's own id name")
    }
}
