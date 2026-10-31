package io.github.basekt.admin

import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.ResourceBuilder
import io.github.basekt.schema.Schema
import io.github.basekt.schema.schema
import io.github.basekt.server.BaseKt
import io.github.basekt.server.Identity
import io.github.basekt.server.IdentityResolver
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Two made-up schemas: an art gallery's exhibits and a community radio station's playlist queue. */
@Serializable
private data class RadioTrack(
    val id: String? = null,
    val title: String,
    val artist: String,
    val genre: String? = null,
    val coverUrl: String? = null,
    val position: Int = 0,
)

private val galleryAndRadio: Schema = schema {
    resource("exhibits") {
        file("photo", "media")
        string("title") { required() }
        string("room")
        enum("wing", listOf("north", "south"))
        string("internalNotes") { hidden() }
        adminView {
            cards(media = "photo", title = "title", subtitle = "room", badge = "wing")
            detailLayout = DetailLayout.SPLIT
        }
        permissions { read("curator"); write("curator") }
    }
    resource<RadioTrack>("tracks") {
        adminView {
            cards {
                media(RadioTrack::coverUrl)
                title(RadioTrack::title)
                subtitle(RadioTrack::artist)
                badge(RadioTrack::genre)
            }
            detailLayout = DetailLayout.SHEET
            reorderable(RadioTrack::position)
        }
        permissions { read("dj"); write("dj") }
    }
    resource("rooms") {
        string("name")
        permissions { read("curator") }
    }
}

class AdminViewsTest {
    private fun problems(build: ResourceBuilder.() -> Unit) = schema {
        resource("exhibits") {
            file("photo", "media")
            string("title")
            int("position")
            int("seq") { sequence() }
            string("secret") { hidden() }
            build()
        }
    }.adminViewProblems()

    @Test
    fun `resource admin views round-trip through schema JSON and reach the panel wire format`() = testApplication {
        assertEquals(emptyList(), galleryAndRadio.adminViewProblems())
        assertEquals(galleryAndRadio, Schema.fromJson(galleryAndRadio.toJson()))

        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = galleryAndRadio
                identities = IdentityResolver { Identity("staff-1", setOf("curator", "dj")) }
            }
            install(BaseKtAdmin)
        }

        val schemaJson = Json.parseToJsonElement(client.get("/admin/api/schema").bodyAsText()).jsonObject
        val resources = schemaJson["resources"]!!.jsonArray.associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }

        val exhibitView = resources.getValue("exhibits").jsonObject["view"]!!.jsonObject
        assertEquals("split", exhibitView["detailLayout"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, exhibitView["orderField"])
        assertEquals("photo", exhibitView["cards"]!!.jsonObject["media"]!!.jsonPrimitive.content)
        assertEquals("wing", exhibitView["cards"]!!.jsonObject["badge"]!!.jsonPrimitive.content)

        val trackView = resources.getValue("tracks").jsonObject["view"]!!.jsonObject
        assertEquals("sheet", trackView["detailLayout"]!!.jsonPrimitive.content)
        assertEquals("position", trackView["orderField"]!!.jsonPrimitive.content)
        assertEquals("coverUrl", trackView["cards"]!!.jsonObject["media"]!!.jsonPrimitive.content)

        assertEquals(JsonNull, resources.getValue("rooms").jsonObject["view"])
    }

    @Test
    fun `invalid card slots and order fields are reported and refused on install`() {
        assertTrue(problems { adminView { cards() } }.single().contains("without naming any slot"))
        assertTrue(problems { adminView { cards(media = "position") } }.single().contains("must be a file or string"))
        assertTrue(problems { adminView { cards(title = "missing") } }.single().contains("isn't a field"))
        assertTrue(problems { adminView { cards(subtitle = "secret") } }.single().contains("a hidden field"))
        assertEquals(emptyList(), problems { adminView { cards(title = "id") } })

        assertTrue(problems { adminView { reorderable("title") } }.single().contains("must be an int or long"))
        assertTrue(problems { adminView { reorderable("secret") } }.single().contains("a hidden field"))
        assertTrue(problems { adminView { reorderable("seq") } }.single().contains("set automatically"))
        assertTrue(
            schema {
                resource("liveQueue") {
                    provided()
                    int("position")
                    adminView { reorderable("position") }
                }
            }.adminViewProblems().single().contains("read-only"),
        )

        val broken = schema {
            resource("exhibits") {
                string("title")
                adminView { reorderable("title") }
            }
        }
        val error = assertFailsWith<Throwable> {
            testApplication {
                application {
                    install(BaseKt) { dataSource = InMemoryDataSource(); schema = broken }
                    install(BaseKtAdmin)
                }
                startApplication()
            }
        }
        assertTrue(generateSequence(error) { it.cause }.any { "admin views are not valid" in it.message.orEmpty() })
    }
}
