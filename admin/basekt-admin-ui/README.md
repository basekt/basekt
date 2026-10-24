# basekt-admin-ui

`install(BaseKtAdminUi)`: a schema-driven admin panel (React, shadcn/ui and Tailwind) prebuilt into the jar, so the host needs no Node. It has a typed Kotlin theme, sign-in by password or token, charts, a visual Markdown editor and links to your site.

```kotlin
dependencies { implementation("io.github.basekt:basekt-admin-ui:0.2.0") }
```

## Use

```kotlin
install(BaseKtAdmin)
install(BaseKtAdminUi) {
    brand { name = "Shop" }
    login { path = "/auth/login" }
    siteLink("Open site", "/")
    theme { radius = Radius.rem(0.5) }
}
```

## Good to know

- The panel renders every screen from the admin API's `GET /schema`: adding a resource needs no panel change.
- Its source is in `web/`. To work on it with hot reload: `npm ci` and `npm run dev` there.

## Documentation

- [Admin panel and themes](https://basekt.github.io/basekt/admin-panel/)
- [Building](https://basekt.github.io/basekt/building/)
- [All modules](../../README.md#modules)
