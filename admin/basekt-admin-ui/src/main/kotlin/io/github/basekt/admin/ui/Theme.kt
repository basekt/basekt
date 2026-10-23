package io.github.basekt.admin.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A shadcn/ui CSS variable the panel's components read. [cssName] is the variable's name without the leading `--`.
 * The corner radius isn't a token: it is [Theme.radius].
 */
@Serializable
public enum class Token(public val cssName: String) {
    /** The page background. */
    Background("background"),

    /** Text on [Background]. */
    Foreground("foreground"),

    /** Card surfaces. */
    Card("card"),

    /** Text on [Card]. */
    CardForeground("card-foreground"),

    /** Popovers, menus and dropdowns. */
    Popover("popover"),

    /** Text on [Popover]. */
    PopoverForeground("popover-foreground"),

    /** Primary buttons and highlights. */
    Primary("primary"),

    /** Text on [Primary]. */
    PrimaryForeground("primary-foreground"),

    /** Secondary buttons. */
    Secondary("secondary"),

    /** Text on [Secondary]. */
    SecondaryForeground("secondary-foreground"),

    /** Muted surfaces, such as table headers. */
    Muted("muted"),

    /** Secondary text, such as descriptions and placeholders. */
    MutedForeground("muted-foreground"),

    /** Hovered and selected items. */
    Accent("accent"),

    /** Text on [Accent]. */
    AccentForeground("accent-foreground"),

    /** Destructive actions and errors. */
    Destructive("destructive"),

    /** Borders. */
    Border("border"),

    /** Input borders. */
    Input("input"),

    /** Focus rings. */
    Ring("ring"),

    /** The first chart colour. */
    Chart1("chart-1"),

    /** The second chart colour. */
    Chart2("chart-2"),

    /** The third chart colour. */
    Chart3("chart-3"),

    /** The fourth chart colour. */
    Chart4("chart-4"),

    /** The fifth chart colour. */
    Chart5("chart-5"),

    /** The sidebar's background. */
    Sidebar("sidebar"),

    /** Text in the sidebar. */
    SidebarForeground("sidebar-foreground"),

    /** The sidebar's primary items. */
    SidebarPrimary("sidebar-primary"),

    /** Text on [SidebarPrimary]. */
    SidebarPrimaryForeground("sidebar-primary-foreground"),

    /** Hovered and active sidebar items. */
    SidebarAccent("sidebar-accent"),

    /** Text on [SidebarAccent]. */
    SidebarAccentForeground("sidebar-accent-foreground"),

    /** The sidebar's borders. */
    SidebarBorder("sidebar-border"),

    /** Focus rings in the sidebar. */
    SidebarRing("sidebar-ring"),
    ;

    public companion object {
        /** The token whose CSS variable is `--[cssName]`, or null. */
        public fun of(cssName: String): Token? = entries.firstOrNull { it.cssName == cssName }
    }
}

/** A colour a [Token] takes: [Oklch], as shadcn's themes use, or [Hex]. */
@Serializable
public sealed interface Color

/**
 * A colour in the OKLCH space, as in `oklch(0.205 0 0)` or `oklch(1 0 0 / 10%)`.
 *
 * @property lightness perceived lightness, from 0 to 1.
 * @property chroma colourfulness, 0 or more (shadcn's themes stay under 0.4).
 * @property hue the hue angle in degrees, from 0 to 360.
 * @property alpha opacity, from 0 to 1.
 */
@Serializable
@SerialName("oklch")
public data class Oklch(
    val lightness: Double,
    val chroma: Double,
    val hue: Double,
    val alpha: Double = 1.0,
) : Color {
    init {
        require(lightness in 0.0..1.0) { "An OKLCH lightness is from 0 to 1, not $lightness." }
        require(chroma.isFinite() && chroma >= 0.0) { "An OKLCH chroma is 0 or more, not $chroma." }
        require(hue in 0.0..360.0) { "An OKLCH hue is from 0 to 360 degrees, not $hue." }
        require(alpha in 0.0..1.0) { "An alpha is from 0 to 1, not $alpha." }
    }
}

/** A colour as hex digits: `#rgb`, `#rrggbb` or `#rrggbbaa`, kept in lowercase. Make one with `Hex("#1e40af")`. */
@Serializable
@SerialName("hex")
@ConsistentCopyVisibility
public data class Hex private constructor(
    /** The digits with their `#`, in lowercase. */
    val value: String,
) : Color {
    public companion object {
        private val pattern = Regex("#([0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})")

        /** Checks [value], such as `#1E40AF`, and returns it as a colour. */
        public operator fun invoke(value: String): Hex {
            val normalized = value.trim().lowercase()
            require(pattern.matches(normalized)) { "'$value' isn't a hex colour like #1e40af." }
            return Hex(normalized)
        }
    }
}

/** The base corner radius, `--radius`. The components derive their smaller and larger radii from it. */
@Serializable
public data class Radius(
    /** The length, 0 or more. */
    val value: Double,
    /** The unit of [value]. */
    val unit: CssUnit,
) {
    init {
        require(value.isFinite() && value >= 0.0) { "A radius is 0 or more, not $value." }
    }

    /** The unit of a [Radius]. */
    @Serializable
    public enum class CssUnit(
        /** The CSS unit. */
        public val css: String,
    ) {
        /** Relative to the root font size. */
        @SerialName("rem") Rem("rem"),

        /** CSS pixels. */
        @SerialName("px") Px("px"),
    }

    public companion object {
        /** A radius in rem, such as `Radius.rem(0.625)`. */
        public fun rem(value: Double): Radius = Radius(value, CssUnit.Rem)

        /** A radius in CSS pixels. */
        public fun px(value: Double): Radius = Radius(value, CssUnit.Px)
    }
}

/**
 * The panel's look: a [radius], and the colour of each [Token] in [light] and [dark] mode. What a theme leaves out
 * keeps [Theme.Default]'s value.
 *
 * Build one with [theme], or import one from shadcn's CSS with [Theme.fromCss].
 */
@Serializable
public data class Theme(
    /** The base corner radius, or null for the default. */
    val radius: Radius? = null,
    /** The colours under `:root`. */
    val light: Map<Token, Color> = emptyMap(),
    /** The colours under `.dark`. */
    val dark: Map<Token, Color> = emptyMap(),
) {
    /** This theme with [other]'s radius and colours on top. */
    public operator fun plus(other: Theme): Theme =
        Theme(other.radius ?: radius, light + other.light, dark + other.dark)

    public companion object {
        /** shadcn/ui's neutral theme: every token in both modes, and a 0.625rem radius. */
        public val Default: Theme by lazy { fromCss(DEFAULT_THEME_CSS) }

        /**
         * Reads a theme from CSS such as ui.shadcn.com/themes gives: a `:root { … }` block for light mode and a
         * `.dark { … }` block for dark mode, in any wrapper such as `@layer base`. Colours are `oklch(…)` or hex.
         * Variables that are neither a [Token] nor `--radius`, such as fonts and shadows, are ignored.
         *
         * @throws IllegalArgumentException when there is neither block, or a token's value isn't a colour it reads.
         */
        public fun fromCss(text: String): Theme = ThemeCss.read(text)
    }
}

/**
 * Builds a [Theme]:
 * ```
 * theme {
 *     radius = Radius.rem(0.5)
 *     light { this[Token.Primary] = Oklch(0.55, 0.2, 260.0) }
 *     dark { this[Token.Primary] = Hex("#93c5fd") }
 * }
 * ```
 */
public fun theme(block: ThemeBuilder.() -> Unit): Theme = ThemeBuilder().apply(block).build()

/** The receiver of [theme] and of `BaseKtAdminUiConfig.theme`. */
public class ThemeBuilder internal constructor() {
    private var base = Theme()
    private val light = mutableMapOf<Token, Color>()
    private val dark = mutableMapOf<Token, Color>()

    /** The base corner radius, or null to keep the default. */
    public var radius: Radius? = null

    /** Starts from [theme], such as one read with [Theme.fromCss]. What this builder sets wins over it. */
    public fun use(theme: Theme) {
        base += theme
    }

    /** Sets light-mode colours. */
    public fun light(block: Palette.() -> Unit) {
        Palette(light).block()
    }

    /** Sets dark-mode colours. */
    public fun dark(block: Palette.() -> Unit) {
        Palette(dark).block()
    }

    internal fun build(): Theme = base + Theme(radius, light.toMap(), dark.toMap())
}

/** The colours of one mode, set with `this[Token.Primary] = Oklch(…)`. */
public class Palette internal constructor(private val colors: MutableMap<Token, Color>) {
    /** Sets [token]'s colour. */
    public operator fun set(token: Token, color: Color) {
        colors[token] = color
    }

    /** [token]'s colour in this palette so far, or null. */
    public operator fun get(token: Token): Color? = colors[token]
}

// shadcn/ui's neutral theme for Tailwind v4.
private const val DEFAULT_THEME_CSS = """
:root {
  --radius: 0.625rem;
  --background: oklch(1 0 0);
  --foreground: oklch(0.145 0 0);
  --card: oklch(1 0 0);
  --card-foreground: oklch(0.145 0 0);
  --popover: oklch(1 0 0);
  --popover-foreground: oklch(0.145 0 0);
  --primary: oklch(0.205 0 0);
  --primary-foreground: oklch(0.985 0 0);
  --secondary: oklch(0.97 0 0);
  --secondary-foreground: oklch(0.205 0 0);
  --muted: oklch(0.97 0 0);
  --muted-foreground: oklch(0.556 0 0);
  --accent: oklch(0.97 0 0);
  --accent-foreground: oklch(0.205 0 0);
  --destructive: oklch(0.577 0.245 27.325);
  --border: oklch(0.922 0 0);
  --input: oklch(0.922 0 0);
  --ring: oklch(0.708 0 0);
  --chart-1: oklch(0.646 0.222 41.116);
  --chart-2: oklch(0.6 0.118 184.704);
  --chart-3: oklch(0.398 0.07 227.392);
  --chart-4: oklch(0.828 0.189 84.429);
  --chart-5: oklch(0.769 0.188 70.08);
  --sidebar: oklch(0.985 0 0);
  --sidebar-foreground: oklch(0.145 0 0);
  --sidebar-primary: oklch(0.205 0 0);
  --sidebar-primary-foreground: oklch(0.985 0 0);
  --sidebar-accent: oklch(0.97 0 0);
  --sidebar-accent-foreground: oklch(0.205 0 0);
  --sidebar-border: oklch(0.922 0 0);
  --sidebar-ring: oklch(0.708 0 0);
}
.dark {
  --background: oklch(0.145 0 0);
  --foreground: oklch(0.985 0 0);
  --card: oklch(0.205 0 0);
  --card-foreground: oklch(0.985 0 0);
  --popover: oklch(0.205 0 0);
  --popover-foreground: oklch(0.985 0 0);
  --primary: oklch(0.922 0 0);
  --primary-foreground: oklch(0.205 0 0);
  --secondary: oklch(0.269 0 0);
  --secondary-foreground: oklch(0.985 0 0);
  --muted: oklch(0.269 0 0);
  --muted-foreground: oklch(0.708 0 0);
  --accent: oklch(0.269 0 0);
  --accent-foreground: oklch(0.985 0 0);
  --destructive: oklch(0.704 0.191 22.216);
  --border: oklch(1 0 0 / 10%);
  --input: oklch(1 0 0 / 15%);
  --ring: oklch(0.556 0 0);
  --chart-1: oklch(0.488 0.243 264.376);
  --chart-2: oklch(0.696 0.17 162.48);
  --chart-3: oklch(0.769 0.188 70.08);
  --chart-4: oklch(0.627 0.265 303.9);
  --chart-5: oklch(0.645 0.246 16.439);
  --sidebar: oklch(0.205 0 0);
  --sidebar-foreground: oklch(0.985 0 0);
  --sidebar-primary: oklch(0.488 0.243 264.376);
  --sidebar-primary-foreground: oklch(0.985 0 0);
  --sidebar-accent: oklch(0.269 0 0);
  --sidebar-accent-foreground: oklch(0.985 0 0);
  --sidebar-border: oklch(1 0 0 / 10%);
  --sidebar-ring: oklch(0.556 0 0);
}
"""
