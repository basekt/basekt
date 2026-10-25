# Admin panel and themes

**The admin panel** (`basekt-admin-ui`) is a React, shadcn/ui and Tailwind app prebuilt into the jar, so the host
needs no Node. It renders every screen from the [admin API](admin-api.md)'s `/schema`, so adding a resource needs no
panel change:

- a sidebar of what the caller may see, and a home screen with the [reports](aggregates-reports.md#reports) they may
  read, as charts, with a [range picker](aggregates-reports.md#rolling-ranges) and a range menu on each card;
- lists with search, filters, sort, cursor paging, bulk delete and CSV export;
- forms with a widget per field type, a [visual Markdown editor with a formula menu](rich-text.md#the-admin-panels-editor)
  for Markdown fields, including a searchable picker for references, and validation errors and hook
  vetoes shown on their fields. A create form asks for the id first when the resource has
  [natural ids](schema.md#natural-ids);
- an error card that says when the server couldn't be reached, shows the [request id](schema.md#request-ids-and-server-errors),
  and copies a report or downloads the page's recent failed calls (kept in memory only) for whoever runs the panel.

It hides what the caller's roles don't allow, signs in with a name and password, a bearer token, or the host's cookie
session ([signing in](#signing-in)), and has light and dark modes.

![The sample shop's products in the admin panel](assets/staff-products.jpg)

## Signing in

With no more set up, the panel rides the host's cookie session, or asks for a bearer token to paste. To let staff sign
in with a name and a password instead, point it at the host app's login route:

```kotlin
install(BaseKtAdminUi) {
    login { path = "/auth/login" }   // POST {"email": …, "password": …} → {"token": …}
}
```

The sign-in screen then asks for the email and the password, posts them as JSON to that route, and keeps the token in
the answer's `token` property like one pasted, sending it as a bearer token. `usernameField`, `usernameLabel`,
`passwordField` and `tokenField` fit other shapes, such as `{"username": …}`, and every setting can also come from
`basekt.adminUi.login`. The path must be on the panel's own server, so a password never goes anywhere else. A 401 or
403 shows the route's own message on the form, and pasting a token stays one click away.

## Links to your site

A record often has a page on the host app's own site: a lesson, a product, a post. Give its resource a link, and the
panel shows a **View on site** button on the record's page and an icon on its row in the list, each opening in a new
tab:

```kotlin
resource("lessons") {
    string("title") { required() }
    string("slug")
    adminLinks {
        link("View on site", "/lessons/{id}")
        link("Preview", "/preview?slug={slug}")
    }
}
```

`{id}` and `{field}` stand for the record's id and a field's value, written into the address percent-encoded, so a value
can't change where the link goes. A link is left out of a record that has no value for one of its placeholders.
`adminLink(label, url)` adds a single link. An address must be a path on the same server (`/lessons/{id}`) or an `http`
or `https` address; the labels are 1 to 40 characters, and a placeholder may name the id or a field the panel can show
(not a hidden one). `BaseKtAdmin` refuses a schema with a link that breaks these, listing every problem when the app
starts.

For a link in the panel's top bar to the whole site, set one on the panel:

```kotlin
install(BaseKtAdminUi) {
    siteLink("Open site", "/")   // or basekt.adminUi.siteLink.label and .url
}
```

The other direction, from a page of your site to the panel, is a plain link to `{path}/r/{resource}/{id}`, such as
`/admin/r/lessons/quadratics`. The panel asks for sign-in if the visitor isn't, and only the app knows who may see the
link.

## Live, read-only resources

A resource served by the application's own code, such as [live data](schema.md#live-data-from-your-own-code), comes in
the admin API's `/schema` with `"readOnly": true`, only the `read` operation, and `filters`: each field a list may
be filtered on and the operators it takes. Its `sortable` and `searchable` fields are the ones its provider can sort
and search. The panel lists it with a **Read-only** badge and no New, edit, delete or bulk delete; its filter menu
offers only those fields and operators, and only those columns sort. Its actions are buttons as on any record, and
the list shows no total, since a provider pages its records without counting them. A resource whose fields take
every operator their type allows has `"filters": null`.

## Markdown fields

A [Markdown field](rich-text.md#the-admin-panels-editor) is edited with a visual editor and a formula menu.

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
