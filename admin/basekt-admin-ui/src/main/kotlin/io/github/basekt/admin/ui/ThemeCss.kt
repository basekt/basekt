package io.github.basekt.admin.ui

import java.math.BigDecimal

/**
 * The one place a [Theme] becomes CSS, and CSS becomes a [Theme]. Nothing else in the module writes CSS.
 */
internal object ThemeCss {
    /** `:root { … }` with the radius and light colours, then `.dark { … }` with the dark colours. */
    fun write(theme: Theme): String = buildString {
        block(":root", theme.light, theme.radius)
        block(".dark", theme.dark, null)
    }

    private fun StringBuilder.block(selector: String, colors: Map<Token, Color>, radius: Radius?) {
        append(selector).append(" {\n")
        if (radius != null) declaration("radius", number(radius.value) + radius.unit.css)
        for (token in Token.entries) colors[token]?.let { declaration(token.cssName, color(it)) }
        append("}\n")
    }

    private fun StringBuilder.declaration(name: String, value: String) {
        append("  --").append(name).append(": ").append(value).append(";\n")
    }

    private fun color(color: Color): String = when (color) {
        is Hex -> color.value
        is Oklch -> buildString {
            append("oklch(").append(number(color.lightness)).append(' ')
            append(number(color.chroma)).append(' ').append(number(color.hue))
            if (color.alpha != 1.0) append(" / ").append(percent(color.alpha))
            append(')')
        }
    }

    /** The shortest exact decimal for [value], such as `0.145`, `1` or `27.325`. */
    private fun number(value: Double): String = BigDecimal(value.toString()).stripTrailingZeros().toPlainString()

    private fun percent(fraction: Double): String =
        BigDecimal(fraction.toString()).movePointRight(2).stripTrailingZeros().toPlainString() + "%"

    private val comments = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    private val blocks = Regex("""(:root|\.dark)\s*\{([^{}]*)}""")
    private val declarations = Regex("""--([A-Za-z0-9-]+)\s*:\s*([^;]+);?""")
    private val oklch = Regex("""oklch\(\s*([^\s/)]+)\s+([^\s/)]+)\s+([^\s/)]+)\s*(?:/\s*([^\s)]+)\s*)?\)""")
    private val radiusValue = Regex("""([0-9]*\.?[0-9]+)(rem|px)""")

    fun read(text: String): Theme {
        val css = comments.replace(text, "")
        var radius: Radius? = null
        val light = mutableMapOf<Token, Color>()
        val dark = mutableMapOf<Token, Color>()
        var found = false
        for (block in blocks.findAll(css)) {
            found = true
            val colors = if (block.groupValues[1] == ":root") light else dark
            for (declaration in declarations.findAll(block.groupValues[2])) {
                val name = declaration.groupValues[1]
                val value = declaration.groupValues[2].trim()
                if (name == "radius") {
                    radius = readRadius(value)
                    continue
                }
                val token = Token.of(name) ?: continue
                colors[token] = readColor(value) ?: throw IllegalArgumentException(
                    "--$name: '$value' isn't a colour BaseKt reads. Use oklch(…) or a hex colour such as #1e40af.",
                )
            }
        }
        require(found) { "The CSS has no :root { … } or .dark { … } block of theme variables." }
        return Theme(radius, light, dark)
    }

    private fun readRadius(value: String): Radius {
        val match = radiusValue.matchEntire(value)
            ?: throw IllegalArgumentException("--radius: '$value' isn't a length in rem or px.")
        val length = match.groupValues[1].toDouble()
        return if (match.groupValues[2] == "rem") Radius.rem(length) else Radius.px(length)
    }

    private fun readColor(value: String): Color? {
        if (value.startsWith("#")) return runCatching { Hex(value) }.getOrNull()
        val match = oklch.matchEntire(value) ?: return null
        val (l, c, h, a) = match.destructured
        val lightness = fraction(l) ?: return null
        val chroma = c.toDoubleOrNull() ?: return null
        val hue = (if (h == "none") 0.0 else h.removeSuffix("deg").toDoubleOrNull()) ?: return null
        val alpha = if (a.isEmpty()) 1.0 else fraction(a) ?: return null
        return runCatching { Oklch(lightness, chroma, hue, alpha) }.getOrNull()
    }

    /** `0.5` or `50%` as 0.5. */
    private fun fraction(text: String): Double? =
        if (text.endsWith("%")) text.dropLast(1).toBigDecimalOrNull()?.movePointLeft(2)?.toDouble() else text.toDoubleOrNull()
}
