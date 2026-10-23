package io.github.basekt.files

import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.server.BaseKt
import io.github.basekt.server.Identity
import io.github.basekt.server.IdentityResolver
import io.github.basekt.storage.BlobListing
import io.github.basekt.storage.memory.InMemoryBlobStore
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BaseKtFilesTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(200) { it.toByte() }

    /** Callers come from an `X-Caller: id:role,role` header. */
    private val callers = IdentityResolver { call ->
        call.request.headers["X-Caller"]?.let { header ->
            Identity(header.substringBefore(':'), header.substringAfter(':', "").split(',').filter { it.isNotEmpty() }.toSet())
        }
    }

    // Two made-up setups: a community site with public avatars any member uploads, and a back office with
    // private contracts only its legal team reads.
    private fun files(store: InMemoryBlobStore = InMemoryBlobStore(), test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                identities = callers
                resources { resource("notes") { string("text") } }
            }
            install(BaseKtFiles) {
                this.store = store
                bucket("avatars") {
                    maxBytes = 4 * KILOBYTE
                    accept("image/png", "image/jpeg")
                    read(everyone)
                    upload(authenticated)
                    delete("moderator")
                }
                bucket("contracts") {
                    read("legal")
                    write("legal")
                }
            }
        }
        test()
    }

    private suspend fun ApplicationTestBuilder.upload(
        bucket: String,
        bytes: ByteArray,
        type: String,
        name: String = "upload.bin",
        caller: String? = "ada:member",
    ): HttpResponse = client.submitFormWithBinaryData(
        "/files/$bucket",
        formData {
            append("file", bytes, Headers.build {
                append(HttpHeaders.ContentType, type)
                append(HttpHeaders.ContentDisposition, "filename=\"$name\"")
            })
        },
    ) { caller?.let { header("X-Caller", it) } }

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun `a member uploads an avatar that anyone can download`() = files {
        val response = upload("avatars", png, "image/png", name = "My Photo (1).png")
        assertEquals(HttpStatusCode.Created, response.status)
        val ref = response.json()
        val key = ref["key"]!!.jsonPrimitive.content
        assertTrue(key.startsWith("avatars/") && key.endsWith("/My-Photo-1-.png"), key)
        assertEquals("image/png", ref["contentType"]!!.jsonPrimitive.content)
        assertEquals("/files/$key", ref["url"]!!.jsonPrimitive.content)
        assertEquals(png.size.toLong(), ref["size"]!!.jsonPrimitive.content.toLong())

        val download = client.get("/files/$key")
        assertEquals(HttpStatusCode.OK, download.status)
        assertContentEquals(png, download.bodyAsBytes())
        assertEquals(ContentType.Image.PNG, download.contentType())
        assertEquals("nosniff", download.headers["X-Content-Type-Options"])
        assertContains(download.headers[HttpHeaders.ContentDisposition]!!, "inline")
        assertContains(download.headers[HttpHeaders.CacheControl]!!, "public")
        assertContains(download.headers["Content-Security-Policy"]!!, "sandbox")

        val head = client.head("/files/$key")
        assertEquals(HttpStatusCode.OK, head.status)
        assertEquals(png.size.toString(), head.headers[HttpHeaders.ContentLength])
        assertEquals(0, head.bodyAsBytes().size)

        val etag = download.headers[HttpHeaders.ETag]!!
        assertEquals(HttpStatusCode.NotModified, client.get("/files/$key") { header(HttpHeaders.IfNoneMatch, etag) }.status)
    }

    @Test
    fun `the bucket's permissions decide who may upload, read and delete`() = files {
        assertEquals(HttpStatusCode.Unauthorized, upload("avatars", png, "image/png", caller = null).status)
        assertEquals(HttpStatusCode.Forbidden, upload("contracts", png, "image/png", caller = "ada:member").status)

        val contract = upload("contracts", "Terms".toByteArray(), "text/plain", caller = "lee:legal").json()["url"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.Unauthorized, client.get(contract).status)
        assertEquals(HttpStatusCode.Forbidden, client.get(contract) { header("X-Caller", "ada:member") }.status)
        val private = client.get(contract) { header("X-Caller", "lee:legal") }
        assertEquals("Terms", private.bodyAsText())
        assertContains(private.headers[HttpHeaders.CacheControl]!!, "private")

        val avatar = upload("avatars", png, "image/png").json()["url"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.Forbidden, client.delete(avatar) { header("X-Caller", "ada:member") }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete(avatar) { header("X-Caller", "mo:moderator") }.status)
        assertEquals(HttpStatusCode.NotFound, client.get(avatar).status)
        assertEquals(HttpStatusCode.NotFound, client.delete(avatar) { header("X-Caller", "mo:moderator") }.status)
    }

    @Test
    fun `the type comes from the bytes, not the client`() = files {
        val html = "<html><script>alert(1)</script></html>".toByteArray()
        val disguised = upload("avatars", html, "image/png", name = "cat.png")
        assertEquals(HttpStatusCode.UnsupportedMediaType, disguised.status)
        assertEquals("unsupported_type", disguised.json()["error"]!!.jsonPrimitive.content)

        // A bucket that takes anything stores the HTML as HTML, and serves it only as a sandboxed attachment.
        val stored = upload("contracts", html, "text/plain", name = "page.txt", caller = "lee:legal").json()
        assertEquals("text/html", stored["contentType"]!!.jsonPrimitive.content)
        val download = client.get(stored["url"]!!.jsonPrimitive.content) { header("X-Caller", "lee:legal") }
        assertContains(download.headers[HttpHeaders.ContentDisposition]!!, "attachment")
        assertEquals("nosniff", download.headers["X-Content-Type-Options"])

        // Plain text keeps a harmless declared type.
        val csv = upload("contracts", "a,b\n1,2\n".toByteArray(), "text/csv", name = "rows.csv", caller = "lee:legal").json()
        assertEquals("text/csv", csv["contentType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an upload over the limit is refused and leaves nothing behind`() {
        val store = InMemoryBlobStore()
        files(store) {
            val response = upload("avatars", png + ByteArray(5000), "image/png")
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals("payload_too_large", response.json()["error"]!!.jsonPrimitive.content)
        }
        runTest { assertEquals(emptyList(), (store as BlobListing).list("avatars/")) }
    }

    @Test
    fun `downloads serve byte ranges`() = files {
        val url = upload("avatars", png, "image/png").json()["url"]!!.jsonPrimitive.content
        val part = client.get(url) { header(HttpHeaders.Range, "bytes=8-15") }
        assertEquals(HttpStatusCode.PartialContent, part.status)
        assertEquals("bytes 8-15/${png.size}", part.headers[HttpHeaders.ContentRange])
        assertContentEquals(png.copyOfRange(8, 16), part.bodyAsBytes())

        val tail = client.get(url) { header(HttpHeaders.Range, "bytes=-4") }
        assertContentEquals(png.copyOfRange(png.size - 4, png.size), tail.bodyAsBytes())

        val outside = client.get(url) { header(HttpHeaders.Range, "bytes=9000-") }
        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, outside.status)
        assertEquals("bytes */${png.size}", outside.headers[HttpHeaders.ContentRange])

        // A range for another version of the file gets the whole file.
        val stale = client.get(url) { header(HttpHeaders.Range, "bytes=0-1"); header(HttpHeaders.IfRange, "\"old\"") }
        assertEquals(HttpStatusCode.OK, stale.status)
    }

    @Test
    fun `unknown buckets, bad keys and bad bodies are clear errors`() = files {
        assertEquals(HttpStatusCode.NotFound, upload("nowhere", png, "image/png").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/files/nowhere/a/b.png").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/files/avatars/%2E%2E/contracts/x").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/files/avatars").status)
        val notMultipart = client.post("/files/avatars") {
            header("X-Caller", "ada:member")
            setBody("raw")
        }
        assertEquals(HttpStatusCode.BadRequest, notMultipart.status)
    }

    @Test
    fun `configuration is checked at install`() {
        for (bad in listOf<BaseKtFilesConfig.() -> Unit>(
            { },
            { store = InMemoryBlobStore() },
            { store = InMemoryBlobStore(); bucket("../up") {} },
            { store = InMemoryBlobStore(); bucket("a") {}; bucket("a") {} },
            { store = InMemoryBlobStore(); bucket("a") { maxBytes = 0 } },
            { store = InMemoryBlobStore(); bucket("a") { accept("images") } },
        )) {
            runCatching {
                testApplication {
                    application {
                        install(BaseKt) { dataSource = InMemoryDataSource(); resources { resource("notes") { string("text") } } }
                        install(BaseKtFiles, bad)
                    }
                    client.get("/")
                }
            }.let { assertTrue(it.isFailure, "expected a failure") }
        }
    }
}
