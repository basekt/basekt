package io.github.basekt.richtext

/**
 * Checks the TeX of one formula in a Markdown field. BaseKt never renders formulas: a browser or an app does, with
 * KaTeX, MathJax or its own renderer. This only decides whether a formula may be stored, so an app that renders with a
 * real TeX parser can pass it here and reject what that parser can't draw.
 */
public fun interface MathValidator {
    /**
     * Returns what is wrong with [tex], in a sentence, or `null` if it may be stored. [display] is `true` for a
     * `$$...$$` block and `false` for an inline `$...$` formula.
     */
    public fun validate(tex: String, display: Boolean): String?

    /** Constants for every validator. */
    public companion object {
        /**
         * The default check: the formula isn't blank or huge, its braces and `\begin`/`\end` and `\left`/`\right` pairs
         * balance, and it uses none of the commands that read files, define macros or make links. It doesn't know
         * which commands a renderer supports.
         */
        public val Basic: MathValidator = MathValidator { tex, _ -> basicMathProblem(tex) }
    }
}

private const val MAX_FORMULA = 2_000

/** Commands that no formula in a lesson or a product description needs, and that a lenient renderer may act on. */
private val forbidden = setOf(
    "input", "include", "write", "read", "openin", "openout", "def", "gdef", "edef", "xdef", "let", "futurelet",
    "newcommand", "renewcommand", "providecommand", "catcode", "csname", "href", "url", "includegraphics", "html",
    "htmlClass", "htmlId", "htmlStyle", "htmlData",
)

private fun basicMathProblem(tex: String): String? {
    if (tex.isBlank()) return "is empty."
    if (tex.length > MAX_FORMULA) return "is longer than $MAX_FORMULA characters."
    if (tex.any { it < ' ' && it != '\n' && it != '\t' }) return "has a control character."
    val commands = Regex("""\\([A-Za-z]+)""").findAll(tex).map { it.groupValues[1] }.toList()
    commands.firstOrNull { it in forbidden }?.let { return "uses \\$it, which isn't allowed." }

    var depth = 0
    var i = 0
    while (i < tex.length) {
        when (tex[i]) {
            '\\' -> i++ // the next character is escaped: \{ \} \$ and so on
            '{' -> depth++
            '}' -> if (--depth < 0) return "has a '}' without a '{'."
        }
        i++
    }
    if (depth != 0) return "has a '{' that is never closed."

    val environments = ArrayDeque<String>()
    for (match in Regex("""\\(begin|end)\{([^}]*)}""").findAll(tex)) {
        val (kind, name) = match.destructured
        if (kind == "begin") {
            environments.addLast(name)
        } else if (environments.removeLastOrNull() != name) {
            return "has \\end{$name} that doesn't match its \\begin."
        }
    }
    if (environments.isNotEmpty()) return "has \\begin{${environments.last()}} that is never ended."
    if (commands.count { it == "left" } != commands.count { it == "right" }) return "has \\left and \\right that don't pair up."
    return null
}
