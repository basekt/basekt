package io.github.basekt.samples.lessons

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LessonsTest {
    private fun site(test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { lessonsModule() }
        test()
    }

    @Test
    fun `a lesson with formulas loads KaTeX and shows them, with its picture from storage`() = site {
        val page = client.get("/lessons/quadratics").bodyAsText()
        assertContains(page, "<span class=\"math inline\">\\(ax^2 + bx + c = 0\\)</span>")
        assertContains(page, "<span class=\"math display\">\\[x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}\\]</span>")
        assertContains(page, "katex@")
        assertContains(page, "<img src=\"/files/images/seed/parabola.png\"")
        assertContains(page, "<strong>formula</strong>")

        val image = client.get("/files/images/seed/parabola.png")
        assertEquals(HttpStatusCode.OK, image.status)
        assertContains(image.headers["Content-Type"].orEmpty(), "image/png")
        assertTrue(image.bodyAsBytes().size > 100)
    }

    @Test
    fun `dollar signs that aren't formulas stay text, and a lesson without formulas never loads KaTeX`() = site {
        val budgeting = client.get("/lessons/budgeting").bodyAsText()
        assertContains(budgeting, "A \$5 coffee a day is \$150 a month")
        assertFalse(budgeting.contains("class=\"math"))
        assertFalse(budgeting.contains("katex"))

        val maps = client.get("/lessons/maps").bodyAsText()
        assertContains(maps, "<strong>legend</strong>")
        assertFalse(maps.contains("katex"))

        // The list page shows the summaries, whose field allows no formulas, and loads nothing extra.
        val index = client.get("/").bodyAsText()
        assertContains(index, "from a \$5 coffee to the rent")
        assertFalse(index.contains("katex"))
        assertEquals(HttpStatusCode.NotFound, client.get("/lessons/nope").status)
    }

    @Test
    fun `writing needs an editor, and the rich text rules apply`() = site {
        fun json(text: String): io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            contentType(ContentType.Application.Json)
            setBody(text)
        }
        val anonymous = client.post("/api/lessons", json("""{"title": "T"}"""))
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)

        suspend fun create(fields: String) = client.post("/api/lessons") {
            bearerAuth("demo-editor")
            contentType(ContentType.Application.Json)
            setBody("""{"title": "New", $fields}""")
        }
        assertEquals(HttpStatusCode.Created, create("\"id\": \"x\", \"body\": \"Area is \$\\\\pi r^2\$.\"").status)

        // `body` allows formulas, `summary` doesn't: the same text is fine in one and just text in the other.
        val summary = create("\"summary\": \"Area is \$\\\\pi r^2\$.\"")
        assertEquals(HttpStatusCode.Created, summary.status, summary.bodyAsText())

        val html = create("\"body\": \"<script>alert(1)</script>\"")
        assertEquals(HttpStatusCode.UnprocessableEntity, html.status)
        assertContains(html.bodyAsText(), "Raw HTML isn't allowed.")

        val macro = create("\"body\": \"\$\\\\input{/etc/passwd}\$\"")
        assertEquals(HttpStatusCode.UnprocessableEntity, macro.status)
        assertContains(macro.bodyAsText(), "uses \\\\input")

        val image = create("\"summary\": \"![a](https://example.test/a.png)\"")
        assertEquals(HttpStatusCode.UnprocessableEntity, image.status)
        assertContains(image.bodyAsText(), "Images aren't allowed.")
    }
}
