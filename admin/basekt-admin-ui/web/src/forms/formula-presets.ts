/** One formula the editor can insert: a label for the tooltip, and the TeX that goes between the dollar signs. */
export interface FormulaPreset {
  id: string
  label: string
  latex: string
}

/** A group of presets, with the short text its tab shows. */
export interface FormulaCategory {
  id: string
  label: string
  icon: string
  presets: FormulaPreset[]
}

const tex = String.raw

/**
 * The formulas offered by the editor's formula menu, grouped the way a lesson author looks for them. Each one is drawn
 * by KaTeX in the menu, so what is shown is what is inserted. A test renders every one, and the server's default
 * formula check accepts every one.
 */
export const formulaCategories: FormulaCategory[] = [
  {
    id: "algebra",
    label: "Algebra",
    icon: "½",
    presets: [
      { id: "frac", label: "Fraction", latex: tex`\frac{a}{b}` },
      { id: "sqrt", label: "Square root", latex: tex`\sqrt{x}` },
      { id: "nthroot", label: "Nth root", latex: tex`\sqrt[n]{x}` },
      { id: "pow", label: "Exponent", latex: tex`x^{2}` },
      { id: "sub", label: "Subscript", latex: tex`x_{i}` },
      { id: "subpow", label: "Subscript and exponent", latex: tex`x_{i}^{2}` },
      { id: "pm", label: "Plus or minus", latex: tex`\pm` },
      { id: "times", label: "Multiply", latex: tex`\times` },
      { id: "cdot", label: "Dot", latex: tex`\cdot` },
      { id: "div", label: "Divide", latex: tex`\div` },
      { id: "abs", label: "Absolute value", latex: tex`\left| x \right|` },
    ],
  },
  {
    id: "calculus",
    label: "Calculus",
    icon: "∫",
    presets: [
      { id: "derivative", label: "Derivative", latex: tex`\frac{df}{dx}` },
      { id: "partial", label: "Partial derivative", latex: tex`\frac{\partial f}{\partial x}` },
      { id: "defint", label: "Definite integral", latex: tex`\int_{a}^{b} f(x)\,dx` },
      { id: "indefint", label: "Indefinite integral", latex: tex`\int f(x)\,dx` },
      { id: "doubleint", label: "Double integral", latex: tex`\iint f(x, y)\,dx\,dy` },
      { id: "sum", label: "Summation", latex: tex`\sum_{i=1}^{n} x_i` },
      { id: "prod", label: "Product", latex: tex`\prod_{i=1}^{n} x_i` },
      { id: "limit", label: "Limit", latex: tex`\lim_{x \to 0} f(x)` },
      { id: "infty", label: "Infinity", latex: tex`\infty` },
      { id: "nabla", label: "Nabla", latex: tex`\nabla` },
    ],
  },
  {
    id: "trig",
    label: "Trigonometry",
    icon: "sin",
    presets: [
      { id: "sin", label: "Sine", latex: tex`\sin \theta` },
      { id: "cos", label: "Cosine", latex: tex`\cos \theta` },
      { id: "tan", label: "Tangent", latex: tex`\tan \theta` },
      { id: "sincos", label: "Pythagorean identity", latex: tex`\sin^2 \theta + \cos^2 \theta = 1` },
      { id: "degree", label: "Degrees", latex: tex`90^\circ` },
      { id: "log", label: "Logarithm", latex: tex`\log_{b} x` },
      { id: "ln", label: "Natural logarithm", latex: tex`\ln x` },
    ],
  },
  {
    id: "greek",
    label: "Greek",
    icon: "α",
    presets: [
      ["alpha", "α"], ["beta", "β"], ["gamma", "γ"], ["delta", "δ"], ["epsilon", "ε"], ["theta", "θ"], ["lambda", "λ"],
      ["mu", "μ"], ["pi", "π"], ["sigma", "σ"], ["phi", "φ"], ["omega", "ω"],
      ["Delta", "Δ"], ["Gamma", "Γ"], ["Theta", "Θ"], ["Lambda", "Λ"], ["Sigma", "Σ"], ["Omega", "Ω"],
    ].map(([name, letter]) => ({ id: `greek-${name}`, label: `${name} (${letter})`, latex: `\\${name}` })),
  },
  {
    id: "relations",
    label: "Relations",
    icon: "≈",
    presets: [
      { id: "neq", label: "Not equal", latex: tex`\neq` },
      { id: "approx", label: "Approximately", latex: tex`\approx` },
      { id: "le", label: "Less than or equal", latex: tex`\le` },
      { id: "ge", label: "Greater than or equal", latex: tex`\ge` },
      { id: "in", label: "Element of", latex: tex`\in` },
      { id: "notin", label: "Not an element of", latex: tex`\notin` },
      { id: "subset", label: "Subset", latex: tex`\subset` },
      { id: "cup", label: "Union", latex: tex`\cup` },
      { id: "cap", label: "Intersection", latex: tex`\cap` },
      { id: "emptyset", label: "Empty set", latex: tex`\emptyset` },
      { id: "therefore", label: "Therefore", latex: tex`\therefore` },
      { id: "forall", label: "For all", latex: tex`\forall` },
      { id: "exists", label: "There exists", latex: tex`\exists` },
      { id: "implies", label: "Implies", latex: tex`\implies` },
      { id: "iff", label: "If and only if", latex: tex`\iff` },
    ],
  },
  {
    id: "matrices",
    label: "Matrices",
    icon: "[ ]",
    presets: [
      { id: "paren", label: "Parentheses", latex: tex`\left( \frac{a}{b} \right)` },
      { id: "bracket", label: "Brackets", latex: tex`\left[ \frac{a}{b} \right]` },
      { id: "brace", label: "Braces", latex: tex`\left\{ x \right\}` },
      { id: "matrix2", label: "2 by 2 matrix", latex: tex`\begin{pmatrix} a & b \\ c & d \end{pmatrix}` },
      { id: "matrix3", label: "3 by 3 matrix", latex: tex`\begin{pmatrix} a & b & c \\ d & e & f \\ g & h & i \end{pmatrix}` },
      { id: "det", label: "Determinant", latex: tex`\begin{vmatrix} a & b \\ c & d \end{vmatrix}` },
      { id: "cases", label: "Cases", latex: tex`\begin{cases} x & x \ge 0 \\ -x & x < 0 \end{cases}` },
    ],
  },
  {
    id: "formulas",
    label: "Formulas",
    icon: "f(x)",
    presets: [
      { id: "quadratic", label: "Quadratic formula", latex: tex`x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}` },
      { id: "pythagorean", label: "Pythagorean theorem", latex: tex`a^2 + b^2 = c^2` },
      { id: "euler", label: "Euler's identity", latex: tex`e^{i\pi} + 1 = 0` },
      { id: "energy", label: "Mass-energy", latex: tex`E = mc^2` },
      { id: "slope", label: "Slope", latex: tex`m = \frac{y_2 - y_1}{x_2 - x_1}` },
      { id: "circle", label: "Area of a circle", latex: tex`A = \pi r^2` },
      { id: "normal", label: "Normal distribution", latex: tex`f(x) = \frac{1}{\sigma \sqrt{2\pi}} e^{-\frac{1}{2}\left(\frac{x-\mu}{\sigma}\right)^2}` },
    ],
  },
]
