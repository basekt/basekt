package io.github.basekt.files

import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.model
import io.github.basekt.server.BaseKt
import io.github.basekt.server.Identity
import io.github.basekt.server.IdentityResolver
import io.github.basekt.storage.BlobKey
import io.github.basekt.storage.memory.InMemoryBlobStore
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

class FileFieldsTest {
    /** A typed resource whose scan is a file, beside the dynamic `members` and their avatars. */
    @Serializable
    data class Letter(val id: String? = null, val subject: String, val scan: String? = null)

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    private val store = InMemoryBlobStore()

    private fun app(test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                identities = IdentityResolver { Identity("staff-1", setOf("staff")) }
                resources {
                    resource("members") {
                        string("name") { required() }
                        file("avatar", "avatars")
                        permissions { read(everyone); write("staff") }
                    }
                    resource(model<Letter>("letters") {
                        file(Letter::scan, "scans")
                        permissions { read("staff"); write("staff") }
                    })
                }
            }
            install(BaseKtFiles) {
                store = this@FileFieldsTest.store
                sweepEvery = null
                bucket("avatars") { accept("image/png"); read(everyone); write("staff") }
                bucket("scans") { read("staff"); write("staff"); deleteUnattachedAfter = null }
            }
        }
        test()
    }

    private suspend fun ApplicationTestBuilder.upload(bucket: String, bytes: ByteArray = png): String =
        client.submitFormWithBinaryData("/files/$bucket", formData {
            append("file", bytes, Headers.build {
                append(HttpHeaders.ContentType, "image/png")
                append(HttpHeaders.ContentDisposition, "filename=\"pic.png\"")
            })
        }).let { Json.parseToJsonElement(it.bodyAsText()).jsonObject["key"]!!.jsonPrimitive.content }

    private suspend fun ApplicationTestBuilder.write(method: String, path: String, json: String): HttpResponse {
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = { contentType(ContentType.Application.Json); setBody(json) }
        return if (method == "POST") client.post(path, build) else client.patch(path, build)
    }

    private suspend fun exists(key: String) = store.head(BlobKey.of(key)) != null

    @Test
    fun `a record may point only at a file that was uploaded to its bucket`() = app {
        val avatar = upload("avatars")
        assertEquals(HttpStatusCode.Created, write("POST", "/api/members", """{"id": "m1", "name": "Ada", "avatar": "$avatar"}""").status)

        val missing = write("POST", "/api/members", """{"name": "Bo", "avatar": "avatars/nope/x.png"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, missing.status)
        val error = Json.parseToJsonElement(missing.bodyAsText()).jsonObject["fields"]!!.jsonArray.single().jsonObject
        assertEquals("missing_reference", error["code"]!!.jsonPrimitive.content)

        val elsewhere = write("POST", "/api/members", """{"name": "Cy", "avatar": "${avatar.replace("avatars/", "scans/")}"}""")
        assertEquals("type", Json.parseToJsonElement(elsewhere.bodyAsText()).jsonObject["fields"]!!.jsonArray.single().jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `replacing or removing a file deletes the old one once no record points to it`() = app {
        val first = upload("avatars")
        val second = upload("avatars")
        write("POST", "/api/members", """{"id": "m1", "name": "Ada", "avatar": "$first"}""")
        write("POST", "/api/members", """{"id": "m2", "name": "Bo", "avatar": "$first"}""")

        write("PATCH", "/api/members/m1", """{"avatar": "$second"}""")
        assertNotNull(store.head(BlobKey.of(first)), "m2 still points to it")
        client.delete("/api/members/m2")
        assertNull(store.head(BlobKey.of(first)))

        write("PATCH", "/api/members/m1", """{"avatar": null}""")
        assertEquals(false, exists(second))
    }

    @Test
    fun `typed models use file fields the same way`() = app {
        val scan = upload("scans")
        assertEquals(HttpStatusCode.Created, write("POST", "/api/letters", """{"id": "l1", "subject": "Lease", "scan": "$scan"}""").status)
        client.delete("/api/letters/l1")
        assertEquals(false, exists(scan))
    }

    @Test
    fun `a rolled-back write deletes nothing`() = app {
        val avatar = upload("avatars")
        write("POST", "/api/members", """{"id": "m1", "name": "Ada", "avatar": "$avatar"}""")
        // Clearing the name fails validation, so the avatar change never happens.
        val refused = write("PATCH", "/api/members/m1", """{"name": null, "avatar": null}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
        assertEquals(true, exists(avatar))
    }

    @Test
    fun `the sweep deletes old uploads no record points to`() = app {
        val kept = upload("avatars")
        val abandoned = upload("avatars")
        val alsoAbandoned = upload("avatars")
        val scan = upload("scans")
        write("POST", "/api/members", """{"name": "Ada", "avatar": "$kept"}""")

        val sweeper = application.attributes[FileFieldsKey]
        assertEquals(0, sweeper.sweep(Clock.System.now()))
        assertEquals(2, sweeper.sweep(Clock.System.now() + 25.hours))
        assertEquals(true, exists(kept))
        assertEquals(false, exists(abandoned))
        assertEquals(false, exists(alsoAbandoned))
        assertEquals(true, exists(scan), "scans keeps unattached uploads")
    }

    @Test
    fun `file fields need their buckets`() {
        assertFailsWith<IllegalArgumentException> {
            testApplication {
                application {
                    install(BaseKt) {
                        dataSource = InMemoryDataSource()
                        resources { resource("members") { file("avatar", "avatars") } }
                    }
                    install(BaseKtFiles) {
                        store = InMemoryBlobStore()
                        bucket("other") { read(everyone) }
                    }
                }
                client.delete("/")
            }
        }
    }
}
