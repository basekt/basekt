package io.github.basekt.richtext

import io.github.basekt.schema.MarkdownOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownTest {
    private val withMath = MarkdownOptions(math = true)
    private val withImages = MarkdownOptions(images = true)

    private fun problems(source: String, options: MarkdownOptions = MarkdownOptions()) = Markdown.check(source, options)

    @Test
    fun `ordinary Markdown is accepted, with tables and links`() {
        val text = "# Title\n\nSome **bold**, _italic_ and ~~struck~~ text with [a link](https://example.test/a?b=1&c=2), " +
            "[mail](mailto:a@b.test), [a path](/shop/x) and [a section](#top).\n\n- one\n- [x] two\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n```\ncode \$x\$ <b>\n```\n"
        assertEquals(emptyList(), problems(text))
    }

    @Test
    fun `raw HTML is refused, as a block or inline`() {
        assertEquals(listOf("Raw HTML isn't allowed."), problems("<div>hello</div>"))
        assertEquals(listOf("Raw HTML isn't allowed."), problems("hello <script>alert(1)</script> there"))
        assertEquals(emptyList(), problems("a `<b>` in code, and 1 < 2 > 0"))
    }

    @Test
    fun `images need the option, and links need a safe scheme`() {
        assertEquals(listOf("Images aren't allowed."), problems("![a cat](https://example.test/cat.png)"))
        assertEquals(emptyList(), problems("![a cat](https://example.test/cat.png)", withImages))
        for (bad in listOf("[x](javascript:alert(1))", "[x](JavaScript:alert(1))", "[x](java&#115;cript:alert(1))", "[x](data:text/html;base64,AAAA)", "<javascript:alert(1)>")) {
            assertTrue(problems(bad).any { "isn't allowed: use http" in it }, bad)
        }
        assertTrue(problems("![x](javascript:alert(1))", withImages).any { "isn't allowed: use http" in it })
        assertEquals(listOf("Control characters aren't allowed."), problems("a\u0000b"))
    }

    @Test
    fun `formulas are found inline and as blocks, and dollars that aren't formulas stay dollars`() {
        assertEquals(emptyList(), problems("Solve \$x^2 + 1 = 0\$ and\n\n\$\$\\frac{a}{b}\$\$\n", withMath))
        assertEquals(emptyList(), problems("It costs \$5 and then \$6 more.", withMath))
        assertEquals(emptyList(), problems("Escaped \\\$x\\\$ and `\$code\$` and\n```\n\$\$ never closed\n```\n", withMath))
        assertEquals(listOf("A \$\$ formula isn't closed."), problems("\$\$ x^2", withMath))
        assertEquals(emptyList(), problems("\$\$ x^2", MarkdownOptions()), "with math off, dollars are text")
    }

    @Test
    fun `formulas are checked by the validator`() {
        assertEquals(listOf("Formula 1 has a '{' that is never closed."), problems("\$\\frac{a{b}\$", withMath))
        assertEquals(listOf("Formula 1 has a '}' without a '{'."), problems("\$a}\$", withMath))
        assertEquals(listOf("Formula 1 uses \\input, which isn't allowed."), problems("\$\\input{/etc/passwd}\$", withMath))
        assertEquals(listOf("Formula 2 uses \\href, which isn't allowed."), problems("\$a\$ then \$\\href{x}{y}\$", withMath))
        assertEquals(listOf("Formula 1 has \\begin{matrix} that is never ended."), problems("\$\$\\begin{matrix} a \$\$", withMath))
        assertEquals(listOf("Formula 1 has \\left and \\right that don't pair up."), problems("\$\\left( a\$", withMath))
        assertEquals(emptyList(), problems("\$\\left( \\frac{a}{b} \\right) \\{x\\}\$ and \$\$\\begin{pmatrix}1&2\\\\3&4\\end{pmatrix}\$\$", withMath))
        assertEquals(listOf("Formula 1 has a custom problem."), Markdown.check("\$x\$", withMath) { _, _ -> "has a custom problem." })
    }

    @Test
    fun `HTML shows formulas for a renderer to draw, with the TeX escaped`() {
        val html = Markdown.toHtml("Area \$a<b\$ is\n\n\$\$\\frac{1}{2}\$\$\n", withMath)
        assertTrue(html.contains("<span class=\"math inline\">\\(a&lt;b\\)</span>"), html)
        assertTrue(html.contains("<span class=\"math display\">\\[\\frac{1}{2}\\]</span>"), html)
        assertEquals("<p>It costs \$5 and \$6.</p>", Markdown.toHtml("It costs \$5 and \$6.", withMath).trim())
        assertFalse(Markdown.toHtml("\$x\$", MarkdownOptions()).contains("class=\"math"), "math off: plain text")
    }

    @Test
    fun `HTML never holds what the author wrote as markup`() {
        val html = Markdown.toHtml("<script>alert(1)</script>\n\n<div onclick=\"x()\">hi</div>\n\nHello <b>there</b>")
        assertFalse(html.contains("<script"), html)
        assertFalse(html.contains("<div"), html)
        assertFalse(html.contains("<b>"), html)
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), html)
        assertTrue(html.contains("hi"), html)
    }

    @Test
    fun `HTML neutralizes unsafe addresses and marks links`() {
        val html = Markdown.toHtml("[x](javascript:alert(1)) [y](java&#115;cript:alert(1)) [ok](https://example.test/?a=1&b=2) <javascript:alert(2)>")
        assertFalse(html.contains("href=\"javascript"), html)
        assertFalse(html.contains("href=\"java"), html)
        assertEquals(3, Regex("href=\"#\"").findAll(html).count(), "two unsafe links and an unsafe autolink")
        assertTrue(html.contains("href=\"https://example.test/?a=1&amp;b=2\""), html)
    }

    @Test
    fun `images are dropped unless allowed`() {
        assertFalse(Markdown.toHtml("![a cat](https://example.test/cat.png)").contains("<img"))
        assertTrue(Markdown.toHtml("![a cat](https://example.test/cat.png)", withImages).contains("<img src=\"https://example.test/cat.png\""))
        assertFalse(Markdown.toHtml("![x](javascript:alert(1))", withImages).contains("javascript"))
    }

    @Test
    fun `tables, task lists and links render`() {
        val html = Markdown.toHtml("| a | b |\n|---|---|\n| 1 | 2 |\n\n[site](https://example.test)")
        assertTrue(html.contains("<table"), html)
        assertTrue(html.contains("<a rel=\"noopener noreferrer\" href=\"https://example.test\">site</a>"), html)
    }

    @Test
    fun `plain text drops the markup and keeps the words and each formula's TeX`() {
        val text = Markdown.toPlainText("# Title\n\nSome **bold** and \$a<b\$ with [a link](https://example.test).\n\n- one\n- two\n\n\$\$\\frac{1}{2}\$\$", withMath)
        assertEquals("Title\nSome bold and a<b with a link.\none\ntwo\n\n\\frac{1}{2}", text)
        assertEquals("<b>Hello</b>", Markdown.toPlainText("<b>Hello</b>"), "raw HTML is only text")
    }

    @Test
    fun `deeply nested and huge texts are handled without a crash`() {
        val nested = ">".repeat(2_000) + " deep"
        Markdown.check(nested)
        Markdown.toHtml(nested)
        assertTrue(Markdown.toPlainText("word ".repeat(20_000)).startsWith("word word"))
    }
}
