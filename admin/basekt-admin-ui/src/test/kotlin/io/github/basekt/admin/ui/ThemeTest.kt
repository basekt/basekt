package io.github.basekt.admin.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ThemeTest {
    // As ui.shadcn.com/themes gives it, with a wrapper, comments and variables BaseKt doesn't theme.
    private val pasted = """
        @layer base {
          :root {
            --radius: 0.5rem;
            --font-sans: Inter, sans-serif; /* not a token */
            --background: oklch(1 0 0);
            --primary: oklch(62.3% 0.214 259.815);
            --border: #E4E4E7;
          }
          .dark {
            --background: oklch(0.141 0.005 285.823);
            --border: oklch(1 0 0 / 10%);
          }
        }
    """.trimIndent()

    @Test
    fun `fromCss reads tokens, percentages, alpha, hex and the radius`() {
        val theme = Theme.fromCss(pasted)
        assertEquals(Radius.rem(0.5), theme.radius)
        assertEquals(Oklch(0.623, 0.214, 259.815), theme.light[Token.Primary])
        assertEquals(Hex("#e4e4e7"), theme.light[Token.Border])
        assertEquals(Oklch(1.0, 0.0, 0.0, alpha = 0.1), theme.dark[Token.Border])
        assertEquals(setOf(Token.Background, Token.Primary, Token.Border), theme.light.keys)
    }

    @Test
    fun `the writer's output reads back to the same theme`() {
        for (theme in listOf(Theme.Default, Theme.fromCss(pasted), theme { radius = Radius.px(6.0); dark { this[Token.Ring] = Hex("#123") } })) {
            assertEquals(theme, Theme.fromCss(ThemeCss.write(theme)))
        }
    }

    @Test
    fun `the default theme sets every token in both modes`() {
        assertEquals(Token.entries.toSet(), Theme.Default.light.keys)
        assertEquals(Token.entries.toSet(), Theme.Default.dark.keys)
        assertEquals(Radius.rem(0.625), Theme.Default.radius)
    }

    @Test
    fun `the writer emits root then dark, in token order`() {
        val css = ThemeCss.write(
            theme {
                radius = Radius.rem(0.75)
                light {
                    this[Token.Ring] = Oklch(0.7, 0.0, 0.0)
                    this[Token.Primary] = Oklch(0.55, 0.2, 260.0)
                }
                dark { this[Token.Primary] = Oklch(0.8, 0.1, 260.0, alpha = 0.5) }
            },
        )
        assertEquals(
            """
            :root {
              --radius: 0.75rem;
              --primary: oklch(0.55 0.2 260);
              --ring: oklch(0.7 0 0);
            }
            .dark {
              --primary: oklch(0.8 0.1 260 / 50%);
            }

            """.trimIndent(),
            css,
        )
    }

    @Test
    fun `the builder starts from an imported theme and overrides it`() {
        val built = theme {
            use(Theme.fromCss(pasted))
            light { this[Token.Primary] = Hex("#1e40af") }
        }
        assertEquals(Hex("#1e40af"), built.light[Token.Primary])
        assertEquals(Hex("#e4e4e7"), built.light[Token.Border])
        assertEquals(Radius.rem(0.5), built.radius)
    }

    @Test
    fun `colours are validated with clear messages`() {
        assertTrue(assertFailsWith<IllegalArgumentException> { Oklch(1.5, 0.0, 0.0) }.message!!.contains("lightness"))
        assertTrue(assertFailsWith<IllegalArgumentException> { Oklch(0.5, -0.1, 0.0) }.message!!.contains("chroma"))
        assertTrue(assertFailsWith<IllegalArgumentException> { Oklch(0.5, 0.1, 400.0) }.message!!.contains("hue"))
        assertTrue(assertFailsWith<IllegalArgumentException> { Hex("blue") }.message!!.contains("hex"))
        assertFailsWith<IllegalArgumentException> { Radius.rem(-1.0) }
    }

    @Test
    fun `fromCss names what it can't read`() {
        val hsl = assertFailsWith<IllegalArgumentException> { Theme.fromCss(":root { --primary: 222.2 47.4% 11.2%; }") }
        assertTrue(hsl.message!!.contains("--primary"), hsl.message)
        assertFailsWith<IllegalArgumentException> { Theme.fromCss("body { color: red; }") }
        assertFailsWith<IllegalArgumentException> { Theme.fromCss(":root { --radius: large; }") }
    }
}
