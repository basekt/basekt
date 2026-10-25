# basekt-openapi

`install(BaseKtOpenApi)`: an OpenAPI 3.1 document of BaseKt's REST API, generated from the schema, for clients in any language to generate typed code from.

```kotlin
dependencies { implementation("io.github.basekt:basekt-openapi:0.2.0") }
```

Builds on: basekt-server.

## Use

```kotlin
install(BaseKt) { /* ... */ }
install(BaseKtOpenApi) {
    title = "Example API"
    security = OpenApiSecurity.Bearer("JWT")
    merge(myRoutesOpenApi)          // the routes the application adds next to BaseKt's
}
```

## Good to know

- One full document for every caller, at `/openapi.json` by default: operations no one may run and hidden fields are left out.
- `schema.toOpenApi(options).toOpenApiJson()` builds the same text without a server, for a checked-in file.

## Documentation

- [OpenAPI](https://basekt.github.io/basekt/openapi/)
- [All modules](../../README.md#modules)
