# Design: structured fields and reference labels

Status: proposal. Not built.

A record can hold only scalars today. What belongs to one record (a question's choices, an invoice's lines, a post's
tags and authors) must be a child resource: its own screen in the panel, its own writes, and its own table or
collection. That is three problems:

1. **Editing is split up.** A question and its four choices are five records on two screens, saved one at a time, so
   an editor can't change them together and a half-saved question is possible.
2. **Documents can't be read as they are.** A MongoDB or Firestore application keeps such parts embedded:
   `{text, options: [{_id, text, isCorrect}], hints: [...], authorIds: [...]}`. `basekt-data-mongo` reads another
   program's collections, but not these documents, because a field can't hold a list or an object.
3. **References show ids.** A list cell shows `67b15b27d20b8d55f81b2d30`, not "Programming". The picker guesses a label
   (`labelOf`, the first searchable field); the list and the detail view don't.

This proposes list fields, object fields with stable item ids, and a declared label per resource. One schema works on
every adapter: a document store keeps a list embedded, and a SQL database keeps it in a side table.

The motivating case is an exam app on MongoDB whose questions embed their options and hints and list their authors,
and whose students' answers store the ids of the options they picked. It is used for the examples below.

## What it looks like

```kotlin
resource("questions") {
    string("text") { required(); markdown { math(); images() } }
    objects("options") {                         // a list of objects, edited inside the question's form
        itemIds()                                // each option keeps an id that answers can point at
        string("text") { required(); markdown { math(); images() } }
        bool("isCorrect")
        items(min = 2, max = 10)
    }
    string("hints") { list(); markdown { math() } } // a list of Markdown strings
    reference("authorIds", "authors") { list(); distinct() }
    adminView { label("text") }
}
resource("authors") {
    string("name") { required() }
    adminView { label("name") }                  // pickers, list cells and detail views show the name
}
```

A create or update carries the whole value, in order:

```json
{"text": "What is $2^3$?",
 "options": [{"id": "65f0…a1", "text": "6", "isCorrect": false}, {"text": "8", "isCorrect": true}],
 "hints": ["Think powers."], "authorIds": ["65e1…07"]}
```

The second option has no id, so the server gives it one; the first keeps its own.

## Part 1: labels for references

Small, panel-only, and useful on its own, so it goes first.

- `adminView { label("name") }` names the field that names a record (a string or enum field, or `id`). Without one,
  the panel keeps today's guess.
- The admin API's list and detail responses carry the labels of the records the page refers to:
  `"labels": {"subjects": {"67b1…30": "Programming"}}`, keyed by target resource. The server reads them with one
  read of the ids per target resource per page, through the resource service so the caller's permissions and scopes
  apply: a record the caller couldn't open gets no label, and its cell shows the id.
- The panel shows the label in list cells (`list-view.tsx` `Cell`), the detail view (`record-view.tsx` `ReadOnly`) and
  the picker (`labelOf` uses the declared field first), linked to the record as now.
- The public API is unchanged. An `expand=` parameter for API clients is a separate question.

Wire change: `AdminView.label`, `labels` on list and detail responses. Regenerate the TypeScript types and the panel
fixtures.

## Part 2: lists on resource fields

`list()` is allowed on a resource's field, not only on an action's input.

**Schema.** A list holds values of the field's type. `rules` and `values` apply to each item. New rules:
`items(min, max)` (how many) and `distinct()` (no repeats, for a set). A required list needs one item, as now. A list
can't be `unique`, `auto` or `hidden`, can't be sorted, searched or totalled in a report. Markdown lists are allowed;
file lists stay refused for now (see Files).

**Values.** `Value.Items` is stored. It still has no order, so it can't be a sort key or a cursor.

**Filters.** One new operator, `has`: a record matches when its list holds any of the given values
(`?authorIds[has]=a1,a2`). `eq`, ranges and `contains` are refused on a list field.

**Integrity.** A reference list is checked like a reference (`checkReferences` already unwraps `Value.Items`), and a
record a list points at can't be deleted (the 0.7.0 rule, using `has`).

## Part 3: object fields

`obj("address") { ... }` is one embedded object; `objects("options") { ... }` is a list of them. Both are a new
`FieldType.OBJECT` whose `Field.fields` declares the sub-fields; `objects` sets `list`.

**Sub-fields** are scalars, scalar lists or Markdown, with `required`, `rules` and `values`. Not allowed inside an
object, for now: another object, `unique`, `auto`, `hidden`, files. One level is enough for the cases seen, and keeps
every adapter's storage simple.

**Item ids.** `itemIds()` gives each item of an `objects` field an `id`. The server generates one for an item that
comes without it (with the source's id generator, so an ObjectId on MongoDB), keeps one that comes with it, and refuses
a list in which two items share an id or an id the record didn't have before. So an item keeps its id through every
edit and reorder, and other records can point at it, the way the exam's answers point at options. Without
`itemIds()`, items are plain values.

**Values.** A new `Value.Object(values: Map<String, Value?>)`, encoded as a JSON object. Unknown sub-fields are
refused, as unknown fields are.

**Writes.** A write replaces the whole value: there is no route to patch one item. That keeps a write atomic and lets
`Auto.Version` catch two editors changing the same list. Item routes can come later.

**Errors** carry a path: `options.1.text`, `hints.0`. `FieldError.field` holds it; a panel maps it to the item.

**Filters.** None on object sub-fields in this version. "Any option is correct" needs `$elemMatch` on MongoDB, a
residual match on Firestore and `EXISTS` on SQL; it is worth doing once a case needs it.

## Storage: documents and tables

One schema, and each adapter keeps a list or an object the way its database does.

| | memory | MongoDB | Firestore | SQL (Exposed) |
|---|---|---|---|---|
| scalar list | as is | array | array | side table `{table}_{field}(owner, position, value)` |
| object | as is | sub-document | map | columns `{field}_{sub}` on the row |
| object list | as is | array of sub-documents | array of maps | side table `{table}_{field}(owner, position, item_id, sub columns…)` |
| `has` | in memory | `{field: {$in: [...]}}` | `array-contains-any` | `EXISTS` on the side table |

**MongoDB.** Arrays and sub-documents are what the database already holds, so an existing collection is read as it
is. `MongoMapping` gains what such a collection needs: the name of an item's id (`_id` in the exam app's options) and
ObjectId paths into items (`options._id`, `authorIds`).

**SQL.** Side tables, not a JSON column: a side table can be indexed, filtered with `has` on any database, and holds
real foreign keys for a reference list. A record's lists are written in its transaction and read with one query per
list field per page (`owner IN (...)`). `migrate()` creates side tables as it creates tables. A JSON column would be
simpler to write and is kept as the alternative if side tables cost too much.

**Firestore.** Arrays of maps, with `array-contains-any` for `has`. An array can't hold arrays, which one level of
nesting never needs.

The contract suite (`DataSourceContract`) gets a list field and an object list field in its schema: order kept, ids
kept, `has`, replace on update, rollback in a transaction.

## Other modules

- **Rich text.** `MarkdownFields` walks into lists and objects and reports problems by path (`options.2.text`).
- **Files.** File fields stay top-level. `referenced()` finds a file by an `EQ` filter; a file inside a list or an
  object would be swept while in use. Allowing them needs `has` (lists) and a nested filter (objects) first.
- **OpenAPI.** `array` and `object` schemas with `additionalProperties: false`, `minItems`/`maxItems`, and the `has`
  parameter.
- **Typed models.** A `List<String>` property becomes a list field; a property of a `@Serializable` class becomes an
  object field, and a `List` of one an object list. An `@ItemId` property marks the item id.
- **CSV export.** Lists joined with `; `, objects as JSON.
- **Audit log.** A list's before and after are logged whole.

## The panel

- A **list editor** replaces the "one per line" textarea: chips for strings and enums, a multi-select picker for a
  reference list (showing labels), one editor per item for a Markdown list.
- An **object list editor**: one card per item with its sub-fields (each through `widgetFor`), add, remove, and drag to
  reorder. An item's id is kept but not shown. A required sub-field or an `items()` rule is checked in the form.
- **List cells** show the first few values as chips and "+3"; an object list shows its count ("4 options") or its first
  sub-field.
- `errorsFrom` maps a path to the item and sub-field it names.
- The filter menu offers `has` for a list field and nothing for an object field.

The question then edits on one screen: text, options (with the correct ones ticked), hints and authors.

## Order of work

1. Labels for references (admin API and panel). No core change.
2. Lists on resource fields, `has`, the four adapters, the panel's list editor.
3. Object lists with item ids, rich text paths, the panel's object list editor.
4. Single objects, when a case needs one.

Each step ships on its own, with the contract suite, OpenAPI and the panel's fixtures updated.

## Open questions

- SQL: side tables or a JSON column (see Storage). Side tables are proposed.
- Where `itemIds()` ids come from on SQL and memory: the source's id generator, as for records, is proposed.
- A cap on items per list, to keep a document under MongoDB's and Firestore's size limits.
- Whether `has` on an object list's sub-field (`options.isCorrect[has]=true`) is needed soon.

## For the exam app in particular

With steps 1 to 3, its questions can be served from its MongoDB collection as they are, with the panel editing a
question, its options, hints and authors on one screen, and the answers' option ids staying valid. The stored text is
still HTML, which a Markdown field refuses on the next write, so the text needs converting in place first (its
`format` field can mark which records are done). The four child resources the spike uses (`questionOptions`,
`questionHints`, `questionAuthors`, `questionPublishers`) go away.

## Related

- [Schema](../docs/schema.md), [Adapters](../docs/adapters.md), [Admin panel](../docs/admin-panel.md),
  [Extending BaseKt](../docs/extending.md)
