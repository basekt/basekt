# Admin panel and themes

**The admin panel** (`basekt-admin-ui`) is a React, shadcn/ui and Tailwind app prebuilt into the jar, so the host
needs no Node. It renders every screen from the [admin API](admin-api.md)'s `/schema`, so adding a resource needs no
panel change:

- a sidebar of what the caller may see, and a home screen with the [reports](aggregates-reports.md#reports) they may
  read, as charts;
- lists with search, filters, sort, cursor paging, bulk delete and CSV export;
- forms with a widget per field type, including a searchable picker for references, and validation errors and hook
  vetoes shown on their fields.

It hides what the caller's roles don't allow, signs in with a bearer token or rides the host's cookie session, and
has light and dark modes.

![The sample shop's products in the admin panel](assets/staff-products.jpg)

## Themes

Its theme is typed Kotlin that becomes CSS variables at runtime, so changing it needs no rebuild:

```kotlin
install(BaseKtAdminUi) {
    brand { name = "Shop"; logo = "/static/logo.svg" }
    theme {
        use(Theme.fromCss(File("theme.css").readText()))   // optional: a theme from ui.shadcn.com/themes
        radius = Radius.rem(0.5)
        light { this[Token.Primary] = Oklch(0.511, 0.262, 276.966) }
        dark { this[Token.Primary] = Hex("#818cf8") }
    }
}
```

Reports are drawn in the theme's `chart-1` to `chart-5` colours.

## Working on the panel

The panel's source is in `admin/basekt-admin-ui/web`. See [Building](building.md#the-admin-panel) for running it with
hot reload.
