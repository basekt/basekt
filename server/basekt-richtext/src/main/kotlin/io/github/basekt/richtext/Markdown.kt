package io.github.basekt.richtext

import io.github.basekt.schema.MarkdownOptions
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/**
 * Checks and renders the Markdown of a field declared with `markdown { }`.
 *
 * The text is CommonMark with GitHub tables, strikethrough and task lists, and, when [MarkdownOptions.math] is on, TeX
 * formulas: `$x^2$` inline and `$$ ... $$` as a block. A dollar sign that doesn't open a formula, such as `$5 and $6`,
 * stays a dollar sign, and with math off every dollar sign does. Raw HTML is never allowed: [check] refuses it, and
 * [toHtml] shows it as text. Links and images may point only at `http`, `https`, `mailto` or a path on the same site.
 */
public object Markdown {
    /**
     * Returns what is wrong with [source], one sentence each, or an empty list if it may be stored: control
     * characters, raw HTML, images where [options] don't allow them, links to other schemes, text nested too deeply
     * to read, and formulas that [mathValidator] refuses. The length is the schema's own check.
     */
    public fun check(
        source: String,
        options: MarkdownOptions = MarkdownOptions(),
        mathValidator: MathValidator = MathValidator.Basic,
    ): List<String> {
        val problems = mutableListOf<String>()
        if (source.any { it < ' ' && it != '\n' && it != '\r' && it != '\t' }) problems += "Control characters aren't allowed."
        val prepared = prepare(source, options.math)
        problems += prepared.problems
        prepared.math.forEachIndexed { index, span ->
            mathValidator.validate(span.tex, span.display)?.let { problems += "Formula ${index + 1} $it" }
        }

        val tree = parse(prepared.text)
        if (tree == null) {
            problems += "The text is nested too deeply."
            return problems
        }
        var html = false
        var images = false
        val links = linkedSetOf<String>()
        tree.walk { node ->
            when (node.type) {
                MarkdownElementTypes.HTML_BLOCK, MarkdownTokenTypes.HTML_TAG -> html = true
                MarkdownElementTypes.IMAGE -> if (!options.images) images = true
                MarkdownElementTypes.LINK_DESTINATION, MarkdownElementTypes.AUTOLINK -> {
                    val target = node.getTextInNode(prepared.text).toString().trim().removeSurrounding("<", ">")
                    if (!safeUrl(target)) links += target
                }
            }
        }
        if (html) problems += "Raw HTML isn't allowed."
        if (images) problems += "Images aren't allowed."
        for (link in links) problems += "The link '${link.take(60)}' isn't allowed: use http, https, mailto or a path on this site."
        return problems
    }

    /**
     * Renders [source] as HTML that is safe to put in a page: no raw HTML (it shows as text), no unsafe link or image
     * addresses, and links with `rel="noopener noreferrer"`. A formula becomes `<span class="math inline">\(tex\)</span>`
     * or `<span class="math display">\[tex\]</span>`, with the TeX escaped, for KaTeX's auto-render or MathJax to draw.
     */
    public fun toHtml(source: String, options: MarkdownOptions = MarkdownOptions()): String {
        val rendered = render(source, options)
        return rendered.substitute { span ->
            val tex = escapeHtml(span.tex)
            if (span.display) "<span class=\"math display\">\\[$tex\\]</span>" else "<span class=\"math inline\">\\($tex\\)</span>"
        }
    }

    /**
     * Returns [source] as plain text for search and excerpts: the words without the markup, paragraphs and list items on
     * their own lines, and each formula as its TeX.
     */
    public fun toPlainText(source: String, options: MarkdownOptions = MarkdownOptions()): String {
        val rendered = render(source, options)
        val text = unescapeHtml(
            rendered.html
                .replace(Regex("</(p|li|h[1-6]|tr|blockquote|pre|ul|ol|table|div)>"), "\n")
                .replace(Regex("<br\\s*/?>"), "\n")
                .replace(Regex("</t[dh]>"), " ")
                .replace(Regex("<[^>]*>"), ""),
        )
        val collapsed = text.lines().joinToString("\n") { it.replace(Regex("[ \t]+"), " ").trim() }.replace(Regex("\n{3,}"), "\n\n").trim()
        return Rendered(collapsed, rendered.math).substitute { it.tex }
    }
}

private val flavour = GFMFlavourDescriptor()

/** Parses [text], or returns `null` if it is nested so deeply that the parser runs out of stack. */
private fun parse(text: String): ASTNode? = try {
    MarkdownParser(flavour).buildMarkdownTreeFromString(text)
} catch (_: StackOverflowError) {
    null
}

/**
 * Visits every node, parents first, without recursion, so deeply nested text can't overflow the stack. The children of
 * code are skipped: what is in a code span or block is text, whatever it looks like.
 */
private fun ASTNode.walk(visit: (ASTNode) -> Unit) {
    val pending = ArrayDeque<ASTNode>()
    pending.addLast(this)
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        visit(node)
        if (node.type == MarkdownElementTypes.CODE_SPAN || node.type == MarkdownElementTypes.CODE_FENCE || node.type == MarkdownElementTypes.CODE_BLOCK) continue
        for (child in node.children.asReversed()) pending.addLast(child)
    }
}

private class Rendered(val html: String, val math: List<MathSpan>) {
    fun substitute(replacement: (MathSpan) -> String): String =
        html.replace(placeholder) { replacement(math[it.groupValues[1].toInt()]) }
}

private fun render(source: String, options: MarkdownOptions): Rendered {
    val prepared = prepare(source, options.math)
    var text = prepared.text
    var tree = parse(text) ?: return tooDeep(source)

    // Raw HTML is shown as text: its `<` becomes an entity before the text is parsed again.
    val raw = BooleanArray(text.length)
    var found = false
    tree.walk { node ->
        if (node.type == MarkdownElementTypes.HTML_BLOCK || node.type == MarkdownTokenTypes.HTML_TAG) {
            for (i in node.startOffset until node.endOffset) raw[i] = true
            found = true
        }
    }
    if (found) {
        text = buildString { text.forEachIndexed { i, c -> if (c == '<' && raw[i]) append("&lt;") else append(c) } }
        tree = parse(text) ?: return tooDeep(source)
    }

    var html = try {
        HtmlGenerator(text, tree, flavour, false).generateHtml().removePrefix("<body>").removeSuffix("</body>")
    } catch (_: StackOverflowError) {
        return tooDeep(source)
    }
    html = html.replace(attribute) { match ->
        val (name, value) = match.destructured
        if (safeUrl(value)) match.value else "$name=\"#\""
    }
    if (!options.images) html = html.replace(Regex("<img[^>]*>"), "")
    html = html.replace("<a href=", "<a rel=\"noopener noreferrer\" href=")
    return Rendered(html, prepared.math)
}

/** What shows for text nested too deeply to parse: the text itself, escaped, with its line breaks. */
private fun tooDeep(source: String): Rendered = Rendered("<p>${escapeHtml(source).replace("\n", "<br>\n")}</p>", emptyList())

private val attribute = Regex("""(href|src)="([^"]*)"""")

private val placeholder = Regex("(\\d+)")

/** One formula found in the text. */
private class MathSpan(val tex: String, val display: Boolean)

/** The text with each formula replaced by a placeholder, so the Markdown parser leaves it alone. */
private class Prepared(val text: String, val math: List<MathSpan>, val problems: List<String>)

private fun placeholderFor(index: Int): String = "$index"

/**
 * Finds the formulas, when [math] is on, and swaps each for a placeholder. Every other dollar sign outside code becomes
 * an escaped one, `\$`: the parser has a math syntax of its own that would otherwise take `$5 and $6` for a formula.
 */
private fun prepare(source: String, math: Boolean): Prepared {
    // The placeholder characters can't come from the author, or a formula could be forged.
    val src = source.replace('', '�').replace('', '�')

    val out = StringBuilder()
    val spans = mutableListOf<MathSpan>()
    val problems = mutableListOf<String>()
    var fence: Char? = null
    var i = 0
    while (i < src.length) {
        if (i == 0 || src[i - 1] == '\n') {
            val end = src.indexOf('\n', i).let { if (it < 0) src.length else it }
            val line = src.substring(i, end).trimStart(' ')
            val marker = line.firstOrNull()?.takeIf { (it == '`' || it == '~') && line.startsWith("$it$it$it") }
            if (fence != null || marker != null) {
                if (marker != null) fence = if (fence == null) marker else if (fence == marker) null else fence
                val stop = minOf(end + 1, src.length)
                out.append(src, i, stop)
                i = stop
                continue
            }
        }
        val c = src[i]
        when {
            c == '\\' && i + 1 < src.length -> {
                out.append(c).append(src[i + 1])
                i += 2
            }
            c == '`' -> {
                val run = src.runLength(i, '`')
                val close = src.findRun(i + run, '`', run)
                val stop = if (close < 0) i + run else close + run
                out.append(src, i, stop)
                i = stop
            }
            c == '$' && math && src.startsWith("$$", i) -> {
                val close = src.indexOf("$$", i + 2)
                if (close < 0) {
                    problems += "A \$\$ formula isn't closed."
                    out.append("\\$\\$")
                    i += 2
                } else {
                    spans += MathSpan(src.substring(i + 2, close).trim(), display = true)
                    out.append(placeholderFor(spans.lastIndex))
                    i = close + 2
                }
            }
            c == '$' && math && inlineClose(src, i) >= 0 -> {
                val close = inlineClose(src, i)
                spans += MathSpan(src.substring(i + 1, close), display = false)
                out.append(placeholderFor(spans.lastIndex))
                i = close + 1
            }
            c == '$' -> {
                out.append("\\$")
                i++
            }
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return Prepared(out.toString(), spans, problems)
}

/** How many [c] in a row start at [from]. */
private fun String.runLength(from: Int, c: Char): Int {
    var n = 0
    while (from + n < length && this[from + n] == c) n++
    return n
}

/** The start of the next run of exactly [length] [c], from [from], or -1. */
private fun String.findRun(from: Int, c: Char, length: Int): Int {
    var j = from
    while (j < this.length) {
        if (this[j] == c) {
            val run = runLength(j, c)
            if (run == length) return j
            j += run
        } else {
            j++
        }
    }
    return -1
}

/**
 * The `$` that closes the inline formula opened at [open], or -1. An opening `$` isn't followed by a space, a closing one
 * isn't preceded by one or followed by a digit, and the formula stays on one line: that is how `$5 and $6` stays money.
 */
private fun inlineClose(src: String, open: Int): Int {
    val next = src.getOrNull(open + 1) ?: return -1
    if (next.isWhitespace() || next == '$') return -1
    var j = open + 1
    while (j < src.length && src[j] != '\n') {
        when (src[j]) {
            '\\' -> j++
            '$' -> if (!src[j - 1].isWhitespace() && src.getOrNull(j + 1)?.isDigit() != true) return j
        }
        j++
    }
    return -1
}

/** Whether a link or image address may be served: `http`, `https`, `mailto`, or no scheme at all. */
internal fun safeUrl(raw: String): Boolean {
    // Browsers decode entities in an attribute and ignore control characters inside a scheme, so `java&#115;cript:`
    // and `java<TAB>script:` both count as `javascript:`.
    val url = unescapeHtml(raw).trim().filter { it >= ' ' }
    val colon = url.indexOf(':')
    val boundary = url.indexOfAny(charArrayOf('/', '?', '#'))
    if (colon < 0 || boundary in 0 until colon) return true
    return url.substring(0, colon).lowercase() in setOf("http", "https", "mailto")
}

internal fun escapeHtml(text: String): String = buildString(text.length) {
    for (c in text) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&#39;")
        else -> append(c)
    }
}

internal fun unescapeHtml(text: String): String = text
    .replace(Regex("&#[xX]([0-9a-fA-F]+);")) { it.groupValues[1].toIntOrNull(16)?.takeIf { code -> code in 1..0xFFFF }?.toChar()?.toString() ?: it.value }
    .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.takeIf { code -> code in 1..0xFFFF }?.toChar()?.toString() ?: it.value }
    .replace("&nbsp;", " ")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&amp;", "&")
