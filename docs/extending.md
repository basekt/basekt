# Extending BaseKt

How to add a capability to BaseKt: a module of your own, or a change to an existing one. It is written from the modules
that were added this way: [`basekt-storefront`](storefront.md), [`basekt-richtext`](rich-text.md) and the admin
panel's links and editor. Read [AGENTS.md](https://github.com/basekt/basekt/blob/main/AGENTS.md) first: its design rules
decide where a piece of code may live.

## Module or core?

| If it is... | It goes in... |
|---|---|
| a capability some apps need and others don't (selling online, rich text, files) | **its own module**, with its own plugin |
| a mechanism every app could use, with no business words in it (a field option, a hook point) | **`core:basekt-schema`** or `server:basekt-server`, as small as it can be |
| a setting only one module understands | the module, kept on the schema through [extensions](#putting-settings-on-a-resource) |

Rule 3 of AGENTS.md is the usual deciding one: the core never knows a product, a lesson or an invoice. A module may
know what it is about (a storefront knows a price) but not what the app sells.

## Adding a module

1. **Create it** under the group that fits (`server/`, `admin/`, `commerce/`, ...) as `basekt-<name>/`, with a
   `build.gradle.kts`:

   ```kotlin
   plugins {
       id("basekt.jvm-library")
       id("basekt.publish")
   }

   description = "BaseKt thing: install(BaseKtThing), what it does in a sentence."

   dependencies {
       api(project(":server:basekt-server"))
       testImplementation(project(":testing:basekt-testing"))
   }
   ```

   Anything a caller's code names in its own signatures is an `api` dependency; the rest is `implementation`. A new
   library goes in `gradle/libs.versions.toml`.
2. **Include it** in `settings.gradle.kts`.
3. **Document it**: a row in the module tables of `README.md` and `docs/modules.md`, a page in `docs/` listed in
   `zensical.toml`, and a sample or a test that shows it in use.
4. **Check it**: the PR checklist in AGENTS.md, and `./gradlew build`.

Modules build with `explicitApi()`, so every public declaration says `public` and has KDoc. Keep what callers don't
need `internal`.

## The plugin

A module is a Ktor plugin with typed configuration, built from `ApplicationConfig`, and a check at install:

```kotlin
public class BaseKtThingConfig internal constructor(config: ApplicationConfig) {
    /** Where it is mounted. `basekt.thing.path`, `/thing` by default. */
    public var path: String = config.propertyOrNull("path")?.getString() ?: "/thing"
}

public val BaseKtThing: ApplicationPlugin<BaseKtThingConfig> = createApplicationPlugin(
    name = "BaseKtThing",
    configurationPath = "basekt.thing",
    createConfiguration = ::BaseKtThingConfig,
) {
    checkNotNull(application.pluginOrNull(BaseKt)) { "Install BaseKt before BaseKtThing." }
    val runtime = application.baseKt          // the schema, the data source and the service
    val problems = runtime.schema.thingProblems()
    require(problems.isEmpty()) { "The schema's things are not valid:\n" + problems.joinToString("\n") { "- $it" } }
    // routes, extensions, ...
    on(MonitoringEvent(ApplicationStarted)) { app -> app.log.info("BaseKt thing serves ...") }
}
```

A library never creates the engine, reads environment variables or keeps global state. Check everything at install and
list **every** problem at once, so the first start tells a person what to fix. A test for that starts the app with
`testApplication { application { ... }; startApplication() }` and looks for the message in the exception's causes.

## Putting settings on a resource

A module that needs its own block in the schema DSL, such as `storefront { }` or `adminLinks { }`, doesn't change the
core. A resource can carry settings under a key of the module's own, as JSON, and the schema keeps them without knowing
what they mean:

```kotlin
@Serializable
public data class Shelf(val label: String)

// What an app writes in its schema: an extension function on Extensible, which resource builders and typed models are.
public fun Extensible.shelf(label: String): Unit = extension("warehouse.shelf", Shelf.serializer(), Shelf(label))

// What the module reads back.
public val Resource.shelf: Shelf? get() = extension<Shelf>("warehouse.shelf")
```

- **Prefix the key** with the module's name, so two modules never clash. Calling the function twice replaces the first
  value, so a module that wants a list stores the list (as `adminLinks { }` does).
- The settings survive the schema's JSON form. The schema checks only that the key is well formed.
- **Validate them yourself**, in a function that returns every problem (`Schema.storefrontProblems()`,
  `Schema.adminLinkProblems()`), and call it from the plugin's install. Refuse what the module couldn't serve: a
  field that doesn't exist, a hidden field, a link that isn't a path.
- A hidden field is never public. A module that shows fields to callers must refuse it.

A **field option** is different: when the whole stack needs to know a string is Markdown, as the panel's editor does,
it is a property of `Field` (`markdown { }`). Add one only when an adapter or a client must read it, keep it as
metadata, and leave the enforcement to the module. See how `basekt-schema` and `basekt-richtext` split the work.

## Taking part in writes

A module that must check or follow every write, whichever route it came from, implements `WriteExtension` and registers
it when it is installed:

```kotlin
private class ThingFields : WriteExtension {
    override suspend fun check(resource: Resource, record: Record, transaction: DataSource): List<FieldError> =
        resource.fields.mapNotNull { field -> /* look at record[field.name], return a FieldError to refuse */ null }
}

// in the plugin:
runtime.service.extend(ThingFields())
```

| Function | When it runs | What it may do |
|---|---|---|
| `check` | before a create or update stores, inside its transaction | refuse by returning `FieldError`s; the write fails with `validation_failed` |
| `written` | inside the transaction, after the after-hooks | write more records that commit or roll back with the write |
| `committed` | after the transaction committed | act on what happened; its failures don't fail the write |
| `ran` | after an action's code returned, before its commit | the same, for actions |

`basekt-audit` and `basekt-files` are written this way. Unlike hooks, which belong to the app, extensions belong to
modules and see every resource. Writes that trusted code makes through raw data access aren't reported to them.

## Serving data without a role

The storefront reads the data source directly, not through the resource service, because it serves visitors no role
describes. That is safe only when the module itself is the whole of what is public:

- project only the fields the schema declares, never `record` as a whole;
- apply the declared filters itself, and ignore any query parameter that isn't one;
- escape everything a record holds, set a strict `Content-Security-Policy`, and rate-limit;
- never take a field the schema marked `hidden()`.

## The admin panel

The panel renders every screen from `GET {path}/schema`, so a feature that needs the panel's help has four parts:

1. **The wire type**: a field on `AdminResource`, `AdminField` or the shell's `ShellConfig`, with a default so older
   callers still work.
2. **The generated types**: `./gradlew :admin:basekt-admin-api:generateTypeScript`, then commit
   `typescript/basekt-admin-api.ts`. A test fails the build when they are stale. The shell's own config is mirrored by
   hand in `web/src/config.ts`; keep the two in step.
3. **The widget or component** in `web/src`. Field widgets are one table (`forms/widgets.tsx`, keyed by field type, with
   a special case such as `field.markdown`); a heavy one is loaded with `React.lazy` so a form without it costs
   nothing, as the Markdown editor is.
4. **Tests**: Vitest with the fixtures in `web/src/test` (every resource and field there lists every property, so a new
   wire field is added to them), and a Kotlin test for the API side.

Build the panel with `./gradlew :admin:basekt-admin-ui:npmBuild`, and test it with `npmTest`.

### The Markdown editor

`forms/markdown-editor.tsx` wraps [Milkdown Crepe](https://milkdown.dev). Things worth knowing before changing it:

- It is uncontrolled: it starts from `value` and reports Markdown through `onChange`. The widget gives it a new `key`
  to start it over after the source box changed the text.
- Its colours are Crepe's `--crepe-color-*` variables, mapped to the panel's theme tokens in `markdown-editor.css`, so
  it follows the theme and dark mode.
- Crepe's **image block** is left out on purpose: it writes the picture's ratio over its alt text.
- Crepe's **formula box needs CodeMirror**, so CodeMirror loads only for a field with `math()`.
- The formula menu's presets are in `forms/formula-presets.ts`. To add one, add it to a group: a test draws every preset
  with KaTeX in strict mode and checks that the server's default formula check accepts it.
- jsdom can't run ProseMirror, so the widget's tests replace the editor with a text box that has the same contract
  (`vi.mock("@/forms/markdown-editor", ...)`). Try changes to the editor itself in a browser, with the lessons sample
  (see [Building](building.md#the-samples)).

## Tests

AGENTS.md asks for tests that prove a module is generic: `testApplication` tests with at least two made-up schemas that
use it differently, on the in-memory data source. Give each schema a small story (a bakery, a nursery, a school) so
that nothing in the module can lean on one app's vocabulary. Cover the failing install, the refused write and the
unsafe input, not only the success.

## Related

- [Building and releasing](building.md)
- [Schema and the REST API](schema.md)
- [Storefront](storefront.md) and [Rich text and formulas](rich-text.md), the two modules this is drawn from
