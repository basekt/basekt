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

## Formulas are optional, and they are text

Formulas are off unless a field says `math()`. Two fields of one resource can differ: in the
[lessons sample](https://github.com/basekt/basekt/tree/main/samples/sample-lessons), `summary` is `markdown { }` and
`body` is `markdown { math() }`. In `summary` a `$` is a dollar sign and nothing else; in `body` `$x^2$` is a formula.
A page that has no formula never loads a math library.

A formula is **not an image**. It is stored as TeX inside the text, so it stays small, searchable and editable, and it
scales and follows the page's colours. The reader's browser or app draws it. On the web that is KaTeX:

```kotlin
val html = Markdown.toHtml(lesson.body, options)    // ... <span class="math display">\[x = \frac{-b}{2a}\]</span> ...
val needsMath = "class=\"math" in html             // load KaTeX only then
```

```html
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.css">
<script defer src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.js"
  onload="document.querySelectorAll('.math').forEach(el =>
    katex.render(el.textContent.slice(2, -2), el, { displayMode: el.classList.contains('display'), throwOnError: false }))">
</script>
```

A native app draws the TeX with its own renderer; GoLearn's Compose apps do this with a Compose LaTeX library.

## Images and storage

A picture, such as a photo or a diagram, is an image: upload it to a `BaseKtFiles` bucket and refer to it from the text
by its path, `![A parabola](/files/images/seed/parabola.png)`, on a field that says `images()`.

Keep each picture as a record with a **file field**, like the sample's `pictures` resource:

```kotlin
resource("pictures") {
    belongsTo("lesson", "lessons") { required() }
    string("alt") { required() }
    file("image", "images") { required() }
}
```

This matters: BaseKt deletes a file that no record points to, and sweeps uploads nobody claimed after a day. A path
written only inside the Markdown claims nothing, so the file would disappear. The record keeps it, and also holds the
alt text and who owns the picture. The upload's `url` goes into the text.

When a formula must be an image, for an email, a PDF or a link preview, which can't run a script, draw it ahead of time
with a TeX renderer that makes SVG, store the SVG in a bucket, and use that. BaseKt doesn't do this for you yet.

## Try it

```bash
./gradlew :samples:sample-lessons:run
```

Open `http://localhost:8080`. The three lessons show a lesson with formulas and a picture from storage, one where `$5`
stays money in a field that allows formulas, and one with no formulas that loads no KaTeX. To write, send
`Authorization: Bearer demo-editor`:

To edit them in the visual editor, open `http://localhost:8080/admin`, press **Sign in**, paste `demo-editor`, open
**Lessons** and a lesson.

```bash
curl -X POST localhost:8080/api/lessons -H "Authorization: Bearer demo-editor" -H "Content-Type: application/json" \
  -d '{"title": "Circles", "body": "Area is $\\pi r^2$."}'
```

## The admin panel's editor

A Markdown field is edited in the admin panel with a **visual editor**: headings, bold and italic, lists, tables, links
and quotes appear as they will look, with a toolbar on selection and a `/` menu for blocks. The **Markdown** tab beside
**Visual** shows the text that is saved, and a person can switch at any time. The editor reads and writes Markdown, so
what it saves is exactly what the plugin checks.

On a field with `math()` a **Formula** button opens a menu of formulas, drawn by KaTeX as they will look, in groups:
algebra, calculus, trigonometry, Greek letters, relations, matrices and well-known formulas. A click puts the formula
at the cursor, inline or, with the box ticked, on a line of its own. Click a formula in the text to edit its TeX and
see it redrawn. A field without `math()` has no formula button, and the editor never turns a `$` into a formula.

A few things to know:

- **Block formulas go on their own lines.** The editor keeps a formula as a block only when `$$` is on a line by itself:

  ```
  $$
  x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}
  $$
  ```

  `$$x = ...$$` on one line is a formula inside a paragraph, and the editor saves it back as `$x = ...$`. BaseKt reads
  both as formulas, but only the lines form keeps a block.
- **Pictures** that are already in the text show in the editor and keep their alt text. To add one, use the Markdown
  tab: `![alt text](/files/images/photo.png)`. The editor has no picture picker yet, and its own image block is left out
  because it saves the picture's ratio over the alt text.
- The editor is a separate chunk, loaded only when a form has a Markdown field. A field with formulas also loads the
  code editor that Milkdown uses for the formula box, which makes it larger.

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
- The admin panel has no picture picker for Markdown yet: add pictures in its Markdown tab.
- A picture named only in the text isn't tracked: keep it as a record with a file field (see above). The check doesn't yet confirm that a `/files/...` path exists.

## Related

- [Schema and the REST API](schema.md)
- [Files and storage](files.md)
