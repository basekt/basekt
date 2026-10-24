# basekt-richtext

`install(BaseKtRichText)`: checks the Markdown fields of a schema on every write (no raw HTML, safe links, formulas, size), and renders them to safe HTML and plain text. Formulas are TeX in the text, drawn by the client with KaTeX or MathJax.

```kotlin
dependencies { implementation("io.github.basekt:basekt-richtext:0.3.0-SNAPSHOT") }
```

In the latest `main`, published as a snapshot until 0.3.0 is released: see [Getting started](https://basekt.github.io/basekt/getting-started/#snapshots).

Builds on: basekt-server.

## Use

```kotlin
string("body") { markdown { math(); images(); maxLength(20_000) } }   // in the schema

install(BaseKt) { /* ... */ }
install(BaseKtRichText)

Markdown.toHtml(text, options)        // safe HTML
Markdown.toPlainText(text, options)   // words only, for search
```

## Good to know

- Formulas are optional per field: `$` is a dollar sign unless the field says `math()`.
- The admin panel edits a Markdown field with a visual editor and a formula menu.

## Documentation

- [Rich text and formulas](https://basekt.github.io/basekt/rich-text/)
- [All modules](../../README.md#modules)
