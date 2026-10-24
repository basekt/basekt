package io.github.basekt.samples.lessons

import io.github.basekt.admin.BaseKtAdmin
import io.github.basekt.admin.adminLink
import io.github.basekt.admin.ui.BaseKtAdminUi
import io.github.basekt.data.DataSource
import io.github.basekt.data.Query
import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.files.BaseKtFiles
import io.github.basekt.files.MEGABYTE
import io.github.basekt.richtext.BaseKtRichText
import io.github.basekt.richtext.Markdown
import io.github.basekt.schema.MarkdownOptions
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import io.github.basekt.schema.schema
import io.github.basekt.server.BaseKt
import io.github.basekt.server.Identity
import io.github.basekt.server.IdentityResolver
import io.github.basekt.storage.BlobKey
import io.github.basekt.storage.BlobStore
import io.github.basekt.storage.memory.InMemoryBlobStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.EngineMain
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Starts the sample on CIO, with the settings in `application.conf`. */
public fun main(args: Array<String>): Unit = EngineMain.main(args)

/**
 * The lessons' data model. Both text fields are Markdown, and only `body` allows formulas: a field that doesn't ask for
 * `math()` never has any, and a `$` in it is just a dollar sign. Pictures are records with a file field, so the file
 * stays stored for as long as a record points at it.
 */
public val lessonsSchema: Schema = schema {
    resource("lessons") {
        string("title") { required(); length(min = 1, max = 120) }
        // Plain Markdown: emphasis, links and lists, but no formulas and no images.
        string("summary") { markdown { maxLength(300) } }
        // Markdown with TeX formulas ($x^2$, $$...$$) and images.
        string("body") { markdown { math(); images(); maxLength(20_000) } }
        hasMany("pictures", "pictures", foreignField = "lessonId")
        // The admin panel shows this on a lesson's page and on its row: the page visitors see.
        adminLink("View on site", "/lessons/{id}")
        permissions { read(everyone); write("editor") }
    }
    resource("pictures") {
        belongsTo("lesson", "lessons") { required() }
        string("alt") { required(); length(max = 200) }
        file("image", "images") { required() }
        permissions { read(everyone); write("editor") }
    }
}

/**
 * The sample's module:
 * - BaseKt on the in-memory adapter, seeded with three lessons;
 * - `install(BaseKtRichText)`, which checks `summary` and `body` on every write;
 * - images in the `images` bucket, served under `/files`;
 * - the admin panel at `/admin`, where `summary` and `body` are edited in a visual Markdown editor;
 * - `GET /` and `GET /lessons/{id}`, which render the Markdown to HTML, and load KaTeX only for a lesson that has a
 *   formula.
 *
 * Writing needs `Authorization: Bearer demo-editor`. This is a demo: use real authentication anywhere else.
 */
public fun Application.lessonsModule() {
    val data = InMemoryDataSource()
    val images = InMemoryBlobStore()
    runBlocking { seed(data, images) }

    install(BaseKt) {
        dataSource = data
        schema = lessonsSchema
        identities = IdentityResolver { call ->
            if (call.request.headers["Authorization"] == "Bearer demo-editor") Identity("editor", setOf("editor")) else null
        }
    }
    install(BaseKtRichText)
    install(BaseKtAdmin)
    install(BaseKtAdminUi) {
        brand { name = "Lessons" }
        // A link in the panel's top bar to the site visitors see.
        siteLink("Open site", "/")
    }
    install(BaseKtFiles) {
        store = images
        bucket("images") {
            maxBytes = 2 * MEGABYTE
            accept("image/png", "image/jpeg", "image/webp")
            read(everyone)
            write("editor")
        }
    }

    // The options come from the schema, so the page renders exactly what the field allows.
    val lessons = lessonsSchema.resource("lessons")!!
    val summaryOptions = lessons.markdownOptions("summary")
    val bodyOptions = lessons.markdownOptions("body")

    routing {
        get("/") {
            val items = data.list(lessons, Query(limit = 100)).items
            val list = items.joinToString("") { lesson ->
                val summary = Markdown.toHtml(lesson.text("summary"), summaryOptions)
                """<article><h2><a href="/lessons/${lesson.text(Resource.ID)}">${escape(lesson.text("title"))}</a></h2>$summary</article>"""
            }
            call.respondText(page("Lessons", "<h1>Lessons</h1>$list", math = false), ContentType.Text.Html)
        }
        get("/lessons/{id}") {
            val lesson = data.get(lessons, call.parameters["id"]!!)
                ?: return@get call.respondText(page("Not found", "<p>No such lesson.</p>", math = false), ContentType.Text.Html, HttpStatusCode.NotFound)
            val summary = Markdown.toHtml(lesson.text("summary"), summaryOptions)
            val body = Markdown.toHtml(lesson.text("body"), bodyOptions)
            val title = escape(lesson.text("title"))
            // KaTeX is only loaded when the rendered lesson holds a formula: a lesson without one costs nothing extra.
            call.respondText(
                page(
                    lesson.text("title"),
                    """<p><a href="/">&larr; Lessons</a> &middot; <a class="edit" href="/admin/r/lessons/${lesson.text(Resource.ID)}">Edit in admin</a></p>""" +
                        """<h1>$title</h1><div class="lead">$summary</div>$body""",
                    math = "class=\"math" in body,
                ),
                ContentType.Text.Html,
            )
        }
    }
}

private fun Resource.markdownOptions(field: String): MarkdownOptions = requireNotNull(field(field)?.markdown) { "$name.$field isn't a Markdown field." }

private fun Map<String, Value?>.text(field: String): String = (this[field] as? Value.Text)?.value.orEmpty()

private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** The KaTeX version the pages load, from a CDN. Pinned, so a lesson looks the same tomorrow. */
private const val KATEX = "0.16.11"

/**
 * A page around [body]. With [math], it loads KaTeX and draws every `span.math` that `Markdown.toHtml` made: the span
 * holds `\(tex\)` or `\[tex\]`, so the script strips the two-character delimiters and renders what is left.
 */
private fun page(title: String, body: String, math: Boolean): String {
    val katex = if (math) {
        """
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@$KATEX/dist/katex.min.css">
<script defer src="https://cdn.jsdelivr.net/npm/katex@$KATEX/dist/katex.min.js"
  onload="document.querySelectorAll('.math').forEach(function (el) {
    katex.render(el.textContent.slice(2, -2), el, { displayMode: el.classList.contains('display'), throwOnError: false })
  })"></script>"""
    } else {
        ""
    }
    return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escape(title)}</title>$katex
<style>
body{font:17px/1.6 system-ui,sans-serif;max-width:42rem;margin:2rem auto;padding:0 1rem;color:#17171a}
a{color:#2d5bff}.lead{color:#555}img{max-width:100%;height:auto;border:1px solid #e4e4e8;border-radius:.5rem}
.math.display{display:block;margin:1rem 0;text-align:center}article{margin:1.5rem 0}
</style></head><body>$body</body></html>"""
}

private suspend fun seed(data: DataSource, images: BlobStore) {
    val lessons = lessonsSchema.resource("lessons")!!
    fun text(value: String) = Value.Text(value)

    val parabola = BlobKey.of("images/seed/parabola.png")
    images.put(parabola, Buffer().apply { write(parabolaPng()) }, "image/png")

    data.create(
        lessons,
        mapOf(
            Resource.ID to text("quadratics"),
            "title" to text("Solving quadratic equations"),
            "summary" to text("Factor, complete the square, or use the **formula**. Costs nothing."),
            "body" to text(
                """
                A quadratic equation has the form ${'$'}ax^2 + bx + c = 0${'$'} with ${'$'}a \ne 0${'$'}.

                The solutions are given by the quadratic formula:

                ${'$'}${'$'}
                x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}
                ${'$'}${'$'}

                Its graph is a parabola:

                ![A parabola opening upward](/files/${parabola.value})

                - if ${'$'}b^2 - 4ac > 0${'$'} there are two real roots
                - if it is ${'$'}0${'$'} there is one
                - if it is negative there are none among the real numbers
                """.trimIndent(),
            ),
        ),
    )
    data.create(
        lessonsSchema.resource("pictures")!!,
        mapOf("lessonId" to text("quadratics"), "alt" to text("A parabola opening upward"), "image" to text(parabola.value)),
    )
    data.create(
        lessons,
        mapOf(
            Resource.ID to text("budgeting"),
            "title" to text("Budgeting a month"),
            "summary" to text("Plan a month of spending, from a ${'$'}5 coffee to the rent."),
            "body" to text(
                "A ${'$'}5 coffee a day is ${'$'}150 a month, so the *habit* costs more than the cup. " +
                    "Dollar signs that don't open a formula stay dollar signs, even in a field where formulas are allowed.",
            ),
        ),
    )
    data.create(
        lessons,
        mapOf(
            Resource.ID to text("maps"),
            "title" to text("Reading a map"),
            "summary" to text("North is usually up."),
            "body" to text("A map has a **legend**, a *scale* and a compass. Find the legend first."),
        ),
    )
}

/** Draws a parabola, so the sample needs no image file. */
private fun parabolaPng(): ByteArray {
    val image = BufferedImage(320, 200, BufferedImage.TYPE_INT_ARGB)
    image.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        color = Color(0xF4F4F5)
        fillRect(0, 0, 320, 200)
        color = Color(0xA1A1AA)
        drawLine(160, 10, 160, 190)
        drawLine(10, 150, 310, 150)
        color = Color(0x2D5BFF)
        stroke = BasicStroke(3f)
        val curve = Path2D.Double()
        for (x in -100..100) {
            val px = 160.0 + x
            val py = 150.0 - (x * x) / 70.0 * 1.0 + 20
            if (x == -100) curve.moveTo(px, py) else curve.lineTo(px, py)
        }
        draw(curve)
        dispose()
    }
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}
