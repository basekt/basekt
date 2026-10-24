package io.github.basekt.richtext

import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.schema
import io.github.basekt.server.BaseKt
import io.github.basekt.server.Identity
import io.github.basekt.server.IdentityResolver
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A made-up school: lessons with formulas, and notices with plain Markdown. */
private val school: Schema = schema {
    resource("lessons") {
        string("title") { required() }
        string("body") { markdown { math(); images(); maxLength(200) } }
        permissions { read(authenticated); write("teacher") }
    }
    resource("notices") {
        string("text") { markdown() }
        string("plain")
        permissions { read(authenticated); write("teacher") }
    }
}

private val identities = IdentityResolver { call ->
    call.request.headers["X-User"]?.let { Identity(it, setOf("teacher")) }
}

private fun HttpRequestBuilder.teacher(json: String? = null) {
    header("X-User", "tess")
    if (json != null) {
        contentType(ContentType.Application.Json)
        setBody(json)
    }
}

private suspend fun HttpResponse.fields(): Map<String, String> =
    Json.parseToJsonElement(bodyAsText()).jsonObject["fields"]?.jsonArray.orEmpty()
        .associate { it.jsonObject["field"]!!.jsonPrimitive.content to it.jsonObject["message"]!!.jsonPrimitive.content }

class RichTextTest {
    private fun app(config: BaseKtRichTextConfig.() -> Unit = {}, test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = school
                this.identities = io.github.basekt.richtext.identities
            }
            install(BaseKtRichText, config)
        }
        test()
    }

    @Test
    fun `good Markdown with formulas is stored as written`() = app {
        val created = client.post("/api/lessons") { teacher("""{"id": "l1", "title": "Squares", "body": "Solve ${'$'}x^2 = 4${'$'}, see [notes](/notes)."}""") }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val read = Json.parseToJsonElement(client.get("/api/lessons/l1") { teacher() }.bodyAsText()).jsonObject
        assertEquals("Solve ${'$'}x^2 = 4${'$'}, see [notes](/notes).", read["body"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a write that breaks the field's options is refused with the field's name`() = app {
        suspend fun create(body: String) = client.post("/api/lessons") { teacher("""{"title": "T", "body": ${Json.encodeToString(kotlinx.serialization.serializer<String>(), body)}}""") }

        val html = create("<script>alert(1)</script>")
        assertEquals(HttpStatusCode.UnprocessableEntity, html.status, html.bodyAsText())
        assertEquals("Raw HTML isn't allowed.", html.fields()["body"])

        val formula = create("Bad ${'$'}\\input{x}${'$'} here")
        assertEquals(HttpStatusCode.UnprocessableEntity, formula.status)
        assertEquals("Formula 1 uses \\input, which isn't allowed.", formula.fields()["body"])

        val several = create("<b>x</b> [y](javascript:z)")
        assertTrue(several.fields().getValue("body").startsWith("Raw HTML isn't allowed. The link 'javascript:z'"))

        val long = create("x".repeat(201))
        assertEquals(HttpStatusCode.UnprocessableEntity, long.status, "the schema caps the length")
        assertEquals(HttpStatusCode.Created, create("![ok](https://example.test/a.png)").status)
    }

    @Test
    fun `updates are checked too, and other fields are left alone`() = app {
        client.post("/api/lessons") { teacher("""{"id": "l1", "title": "T", "body": "fine"}""") }
        val bad = client.patch("/api/lessons/l1") { teacher("""{"body": "<iframe></iframe>"}""") }
        assertEquals(HttpStatusCode.UnprocessableEntity, bad.status)
        assertEquals(HttpStatusCode.OK, client.patch("/api/lessons/l1") { teacher("""{"title": "New title"}""") }.status)
        assertEquals(
            HttpStatusCode.Created,
            client.post("/api/notices") { teacher("""{"text": "ok", "plain": "<b>plain fields may hold anything</b>"}""") }.status,
        )
        assertEquals(HttpStatusCode.UnprocessableEntity, client.post("/api/notices") { teacher("""{"text": "${'$'}x${'$'}<b>"}""") }.status)
        assertEquals(HttpStatusCode.Created, client.post("/api/notices") { teacher("""{"text": "costs ${'$'}5"}""") }.status)
    }

    @Test
    fun `an app can bring its own formula check`() = app({ mathValidator = MathValidator { tex, _ -> if ("\\sqrt" in tex) "can't draw roots." else null } }) {
        assertEquals(HttpStatusCode.Created, client.post("/api/lessons") { teacher("""{"title": "T", "body": "${'$'}x${'$'}"}""") }.status)
        val refused = client.post("/api/lessons") { teacher("""{"title": "T", "body": "${'$'}\\sqrt{x}${'$'}"}""") }
        assertEquals("Formula 1 can't draw roots.", refused.fields()["body"])
    }

    @Test
    fun `BaseKt has to be installed first`() {
        val error = assertFailsWith<Throwable> {
            testApplication {
                application { install(BaseKtRichText) }
                startApplication()
            }
        }
        assertTrue(generateSequence(error) { it.cause }.any { "Install BaseKt before BaseKtRichText" in it.message.orEmpty() })
    }
}
