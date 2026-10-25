# OpenAPI

**`basekt-openapi`** describes the [REST API](schema.md) as an OpenAPI 3.1 document, generated from the schema, so a
client in any language can generate typed code for it. It is installed after `BaseKt`:

```kotlin
install(BaseKtOpenApi) {                        // basekt-openapi
    title = "Example API"
    version = "2.1.0"
    security = OpenApiSecurity.Bearer("JWT")    // say how callers sign in; BaseKt doesn't know your auth module
}
```

The document is served at `GET /openapi.json`, open to everyone. `path`, `title`, `version`, `description` and
`servers` can also come from the `basekt.openapi` section of `ApplicationConfig`. The page sizes, the base path and the
request id header are BaseKt's own settings, so the document always matches what is served.

## What it describes

| Route | In the document as |
|---|---|
| `GET /api/{resource}` | `{resource}.list`: every `field=` and `field[op]=` filter the field's type takes, `sort` (only real field names), `limit` (with BaseKt's default and maximum) and `cursor`, answering a `{resource}.page` |
| `POST /api/{resource}` | `{resource}.create`, with a `{resource}.create` body: required fields, the id the client may give, no automatic or hidden fields, nothing else (`additionalProperties: false`) |
| `GET`, `PATCH`, `DELETE /api/{resource}/{id}` | `.get`, `.update` (a `{resource}.patch` body, which may carry the [version](concurrent-writes.md) it last read) and `.delete` |
| `GET /api/{resource}/{id}/{relation}` | `.relation.{name}`: the record a `belongsTo` points to, or a page of a `hasMany`, which takes the target's filters |
| `POST /api/{resource}[/{id}]/actions/{name}` | `.action.{name}`, for [actions and workflow transitions](actions-workflows.md), with an `{resource}.{name}.input` body |
| `GET /api/aggregate/{resource}` | `.aggregate`, with `groupBy`, the measures and the filters |
| `GET /api/schema` | `basekt.schema` |

Fields are JSON Schema: `minLength` and `maxLength` from `length()`, `pattern` from `pattern()`, `minimum` and `maximum`
from `min()` and `max()`, `enum` from enum values, `int32` and `int64` formats, `date-time` for instants, and a field
that may be missing is `["type", "null"]`. A decimal is a string in a record, so no precision is lost, and a string or a
number in a body. A reference names its target in its description, automatic fields are `readOnly`, and a Markdown
field is `contentMediaType: text/markdown`.

Every response carries the request id header. Errors are `basekt.ApiError`, with `basekt.ErrorCode` and
`basekt.FieldError`, and each operation lists the statuses it can answer: `401` and `403` only where its access needs
them, `404` where a record is named, `409` where an id, a version or a workflow state can conflict, `422` for writes, and `503` on every resource route,
for a signed-in caller whose [server roles](permissions.md#roles-from-the-server) couldn't be looked up.

Names can't clash with yours: a resource's own schemas are `{resource}`, `{resource}.create` and so on, BaseKt's are
`basekt.*`, and operation ids are `{resource}.{operation}`.

## One document for every caller

The document is the **full** one, the same for everyone, unlike the [admin API](admin-api.md)'s per-caller schema. A
client generates its types once, at build time, and a document that changed with the caller who fetched it would make
that file depend on who ran the build. So:

- an operation **no one** may run (its access is "nobody") is left out, since no caller can use it;
- a **hidden** field is never in it;
- every other operation is in, and its description says who may run it ("Needs one of the roles: staff."), with the
  `401` and `403` answers it can give. With `security` set, an operation that needs a caller requires the scheme, and
  one open to everyone takes it optionally.

## A checked-in file

To check the document into a client repository and fail its build when it drifts, write it to a file at build time
from the application itself. A small `main` in the test sources starts the application with `testApplication` and
saves what it serves:

```kotlin
fun servedOpenApi(): String {
    var document = ""
    testApplication {
        environment { config = ApplicationConfig("application.conf") }   // plus test settings, such as a fresh database
        document = client.get("/openapi.json").bodyAsText()
    }
    return document
}

fun main(args: Array<String>) = File(args.single()).writeText(servedOpenApi())
```

```kotlin
// build.gradle.kts
val generateOpenApi by tasks.registering(JavaExec::class) {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.example.OpenApiFileKt")
    args(layout.projectDirectory.file("openapi/openapi.json").asFile.absolutePath)
}
```

A test then compares the file with `servedOpenApi()` and fails when they differ, and the client repository generates
its types from the file. The document is pretty-printed with a stable order, so the file only changes when the API
does. Without an application to start, `schema.toOpenApi(OpenApiOptions(...))` builds the same document from a schema,
given the same settings, and `.toOpenApiJson()` renders it.

The [sample shop](https://github.com/basekt/basekt/tree/main/samples/sample-shop) does all of this: its
`openapi/openapi.json` is checked in, `OpenApiFileTest` fails the build when it is stale, and CI generates a TypeScript
client from it with `openapi-typescript` and `openapi-fetch` and type-checks code that uses it:

```bash
./gradlew :samples:sample-shop:generateOpenApi
```

## Routes you add

Routes an application serves next to BaseKt's aren't in the document. Describe them in an OpenAPI document of your own,
hand-written or generated, and merge it in:

```kotlin
install(BaseKtOpenApi) {
    merge("""{"paths": {"/health": {"get": {"operationId": "health", "responses": {"200": {"description": "Up."}}}}}}""")
}
```

`merge` takes a `JsonObject` or JSON text, and can be called more than once. Paths, operations, components and tags are
added; the same method on the same path, or a component with the same name and different content, is refused at
install, naming every clash. `info`, `servers` and `security` stay BaseKt's. The same merge is
`document.mergeOpenApi(other)` outside the plugin. Other BaseKt modules' routes, such as [files](files.md) and the
[admin API](admin-api.md), are merged the same way when a client needs them.

## Related

- [Schema and the REST API](schema.md)
- [Admin API](admin-api.md), whose TypeScript types are generated from its Kotlin classes instead
