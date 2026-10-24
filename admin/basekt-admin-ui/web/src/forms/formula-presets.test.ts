import katex from "katex"
import { describe, expect, it } from "vitest"
import { formulaCategories } from "@/forms/formula-presets"

const presets = formulaCategories.flatMap((category) => category.presets)

describe("the formula presets", () => {
  it("has groups and formulas with their own ids", () => {
    expect(formulaCategories.length).toBeGreaterThanOrEqual(6)
    expect(new Set(presets.map((preset) => preset.id)).size).toBe(presets.length)
    expect(new Set(formulaCategories.map((category) => category.id)).size).toBe(formulaCategories.length)
  })

  it("draws every formula with KaTeX, without a fallback", () => {
    for (const preset of presets) {
      expect(() => katex.renderToString(preset.latex, { throwOnError: true, strict: "error" }), preset.id).not.toThrow()
    }
  })

  it("holds nothing the server's default formula check refuses", () => {
    for (const preset of presets) {
      // The commands BaseKt's MathValidator.Basic forbids, and a `$` that would end the formula early.
      expect(preset.latex, preset.id).not.toMatch(/\\(input|include|write|read|def|let|newcommand|href|url|includegraphics|html)/)
      expect(preset.latex, preset.id).not.toContain("$")
      const lefts = preset.latex.match(/\\left\b/g)?.length ?? 0
      const rights = preset.latex.match(/\\right\b/g)?.length ?? 0
      expect(lefts, preset.id).toBe(rights)
      expect(preset.latex.split("{").length, preset.id).toBeGreaterThan(0)
    }
  })
})
