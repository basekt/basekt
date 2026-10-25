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

With no more set up, the panel rides the host's cookie session, or asks for a bearer token to paste. It can also sign
staff in with a name and a password through the host's login route, or through an identity provider. Whichever way, the
token is kept like a pasted one: in the tab's `sessionStorage` when "Keep me signed in in this tab" is ticked, in
memory otherwise, never in `localStorage`. Pasting a token stays one click away.

### A login route

Point the panel at the host app's login route:

```kotlin
install(BaseKtAdminUi) {
    login { path = "/auth/login" }   // POST {"email": …, "password": …} → {"token": …}
}
```

The sign-in screen then asks for the email and the password, posts them as JSON to that route, and keeps the token in
the answer's `token` property like one pasted, sending it as a bearer token. `usernameField`, `usernameLabel`,
`passwordField` and `tokenField` fit other shapes, such as `{"username": …}`, and every setting can also come from
`basekt.adminUi.login`. The path must be on the panel's own server, so a password never goes anywhere else. A 401 or
403 shows the route's own message on the form.

### Refreshing the token

Short-lived tokens need refreshing, or staff are signed out each time one expires. The login route may also answer a
refresh token and the token's life in seconds; give the panel the route that renews them:

```kotlin
install(BaseKtAdminUi) {
    login {
        path = "/auth/login"           // → {"token": …, "refreshToken": …, "expiresIn": 900}
        refreshPath = "/auth/refresh"  // POST {"refreshToken": …} → the same shape
    }
}
```

A minute before the token expires (halfway, for one that lives under two minutes), the panel posts the refresh token to
`refreshPath` and keeps what comes back. An answer without a new refresh token keeps the old one, though a route that
issues a new one each time, and refuses one used twice, is safer. Without `expiresIn`, the panel reads the expiry from
the token when it is a JWT, and leaves an opaque token alone. When the route refuses, the panel signs the viewer out
and says their session ended; when the server can't be reached it tries again every few seconds while the token is
still valid. A tab reopened after the token expired refreshes before it loads anything.

The refresh token goes to `refreshPath` and nowhere else: never into the API's calls, a URL or an error report. Without
a `refreshPath`, the panel doesn't keep it at all. `refreshTokenField` and `expiresInField` rename the properties
(`basekt.adminUi.login.refreshPath`, and so on). The [sample shop](building.md#the-samples) has a login route that
answers a 15-minute JWT and a refresh token that works once, in `StaffLogin.kt`.

### An identity provider

The panel can run an identity provider's own sign-in instead, and send the provider's **ID token** as the bearer token.
The host checks that token as it would any from the provider (with `basekt-auth`'s JWT support, against the provider's
keys), and the provider's SDK refreshes it in the browser. The SDK keeps the provider's refresh token and sends it only
to the provider; the panel never sees it. Its code loads only when a viewer signs in, so a panel without a provider
costs nothing more.

**Firebase Authentication**, from the project's Web config:

```kotlin
install(BaseKtAdminUi) {
    firebase {
        apiKey = "AIza…"                        // the Web config's key, public by design
        authDomain = "my-project.firebaseapp.com"
        providers = listOf("password", "google.com")
    }
}
```

`password` shows an email and password form; each other id, such as `google.com`, `microsoft.com`, `apple.com`,
`oidc.<name>` or `saml.<name>`, a "Continue with" button that opens the provider in a popup. Enable each one in the
Firebase console, and add the panel's host to its authorized domains. Firebase keeps its session in the tab like the
panel does.

**OpenID Connect**, with the authorization code flow and PKCE:

```kotlin
install(BaseKtAdminUi) {
    oidc {
        issuer = "https://id.example.test/realms/staff"
        clientId = "admin-panel"                            // a public client
        label = "staff account"                             // "Continue with staff account"
        // scopes = listOf("openid", "profile", "email", "offline_access") by default
    }
}
```

Register `https://<your host>{path}/signed-in` (such as `https://example.test/admin/signed-in`) as the client's
redirect URI. The panel reads the issuer's discovery document, sends the viewer there, and brings them back to the page
they were on. Ask for `offline_access` so the issuer gives a refresh token: without one, the panel signs the viewer out
when the ID token expires. The issuer must allow the panel's origin for its token endpoint (CORS).

Settings can come from `basekt.adminUi.firebase` and `basekt.adminUi.oidc` too. One provider at a time, and not with a
login route.

### What is checked

`BaseKtAdminUi` checks its sign-in settings when it is installed, and lists every problem at once: a login or refresh
path that isn't a path on this server, a refresh route without a login route, blank field names, a Firebase auth domain
that isn't a host name or a provider id it can't use, an OIDC issuer that isn't `https` (`http` only on `localhost`),
a missing client id, scopes without `openid`, and two ways of signing in configured at once.

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
