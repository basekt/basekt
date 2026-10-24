# Rich text and formulas

**Rich text** (`basekt-richtext`) lets a field hold formatted text, with TeX formulas, and keeps it safe. The text is
stored as Markdown in an ordinary text column. That keeps it portable: the same string renders on the web, in a native
app or in an email, it diffs and searches well, and a client can be written in anything.

```kotlin
resource("lessons") {
    string("title") { required() }
    string("body") { markdown { math(); images(); maxLength(20_000) } }
}

install(BaseKt) { /* ... */ }
install(BaseKtRichText)
```

A field with `markdown { }` is still a string field: every data adapter stores it as text, filters and sorts treat it as
text, and the admin panel edits it in a roomy Markdown box that shows its limit.

## What is allowed

The text is CommonMark with GitHub tables, strikethrough and task lists.

| Option | Default | What it does |
|---|---|---|
| `math()` | off | allows TeX formulas: `$x^2$` inline and `$$ ... $$` as a block |
| `images()` | off | allows images that point at `http`, `https` or a path on the same site |
| `maxLength(n)` | 50 000 | caps the text. The schema enforces it, with or without the plugin |

`install(BaseKtRichText)` checks every create and update of a Markdown field, from every route, inside the write's
transaction. A write that breaks the rules fails with `validation_failed` and a message on the field:

- **Raw HTML** is never allowed. A write that contains `<script>` or a `<div>` is refused.
- **Links and images** may point only at `http`, `https`, `mailto` or a path. `javascript:`, `data:` and the same
  scheme hidden with entities or control characters are refused.
- **Images** are refused unless the field says `images()`.
- **Formulas** go through the `mathValidator`. The default refuses an empty or very long formula, unbalanced braces,
  `\begin` without its `\end`, `\left` without `\right`, and commands that read files, define macros or make links.
- **Control characters** and text nested too deeply to read are refused.

A dollar sign that doesn't open a formula stays a dollar sign: `It costs $5 and $6` is text. An opening `$` isn't
followed by a space, a closing one isn't preceded by one or followed by a digit, and an inline formula stays on one
line. Dollar signs in code spans and fenced code are never formulas, and `\$` is always a dollar sign.

## Rendering

BaseKt doesn't draw formulas. A browser or an app does, so the server never needs a TeX engine. It gives you what to
draw:

```kotlin
val options = resource.field("body")!!.markdown!!

Markdown.toHtml(text, options)        // safe HTML
Markdown.toPlainText(text, options)   // words only, for search and excerpts
Markdown.check(text, options)         // the same check the plugin makes
```

`toHtml` escapes everything an author wrote as markup, so it is safe to put in a page even if the stored text predates
the check: raw HTML shows as text, unsafe addresses become `#`, links get `rel="noopener noreferrer"`, and images are
dropped where the field doesn't allow them. A formula becomes a span with the TeX escaped:

```html
<span class="math inline">\(x^2\)</span>
<span class="math display">\[\frac{1}{2}\]</span>
```

Draw those on the web with [KaTeX's auto-render](https://katex.org/docs/autorender), which reads `\(...\)` and `\[...\]`
by default, or with MathJax. A native app takes the TeX from the stored Markdown with the same rules, or from
`toPlainText`.

`toPlainText` is for search: it drops the markup, puts paragraphs and list items on their own lines, and keeps each
formula as its TeX.

## Your own formula check

The default check doesn't know which commands your renderer supports. If you render with a real TeX parser, give the
plugin a validator that asks it:

```kotlin
install(BaseKtRichText) {
    mathValidator = MathValidator { tex, display -> if (myParser.canDraw(tex)) null else "uses a command we can't draw." }
}
```

Return a sentence that continues "Formula 2 ...", or `null` to accept.

## Limits

- The text of an action's input isn't checked, only the fields of records. Check it in the action, with `Markdown.check`.
- Writes that trusted code makes through raw data access, such as a hook's `data`, aren't checked.
- The admin panel edits Markdown as text. A visual editor is a separate piece of work.
- Links to files go through [files and storage](files.md); use a path or `https` address in the text.

## Related

- [Schema and the REST API](schema.md)
- [Files and storage](files.md)
