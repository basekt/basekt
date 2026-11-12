# Notifications

`install(BaseKtNotify)` (`basekt-notify`) sends your app's events to a channel that staff choose in the admin panel.
Your code says *what happened* (`notifier.publish("enrolled", "Ana enrolled")`); a settings resource says *where it goes*,
whether it is on, and which role to mention. Changing a route applies to the next event, with no restart or deploy.

Where messages go sits behind the `ChannelSender` SPI. `basekt-notify-discord` sends to Discord channel webhooks, so you
need no bot or gateway connection for it.

```kotlin
dependencies {
    implementation("io.github.basekt:basekt-notify:0.3.0-SNAPSHOT")
    implementation("io.github.basekt:basekt-notify-discord:0.3.0-SNAPSHOT")
}
```

## Use

```kotlin
// 1. Declare the routes resource in the schema. Callers with these roles can read and edit routes.
schema {
    notifyRoutes(editors = setOf("admin"))
}

// 2. Install it after BaseKt, with a sender. Webhook URLs come from your configuration or secret store.
install(BaseKtNotify) {
    sender = DiscordWebhookSender(
        mapOf(
            "announcements" to environment.config.property("discord.announcements").getString(),
            "staff" to environment.config.property("discord.staff").getString(),
        ),
    )
}

// 3. Publish events from anywhere in your code.
application.notifier.publish("enrolled", "Ana enrolled in Year 3")
```

Staff then open the `notifyRoutes` resource in the admin panel (or the REST API) and add a route per event:

| Field | Meaning |
|---|---|
| `event` | The event key your code publishes, such as `enrolled`. Unique. |
| `enabled` | Off sends nothing. |
| `destination` | A destination name the sender has, such as `announcements`. |
| `mention` | Optional role id to mention. |

An event with no route sends nothing, so a new event is safe to ship before anyone sets it up.

## Why destinations are names

A webhook URL is a secret, and a field any admin can read isn't a place for one (a `hidden()` field can't be edited
through the admin either). So the settings store only a destination *name*. The URLs stay in your configuration, and the
sender maps each name to its URL. A route can only be saved when its destination is one the sender has, so a typo is
refused when staff save it instead of silently losing events.

## Guarantees

- **`publish` never throws.** A send that fails, or a route naming a destination the sender no longer has, goes to
  `onError` (a warning in the log by default) and not to the caller, so a Discord outage can't break the code that
  published the event.
- **No surprise mentions.** `DiscordWebhookSender` turns off `@everyone`, `@here` and user mentions in the text. Only the
  role a route names is pinged.
- **Secrets stay put.** Webhook URLs are checked when the sender is built and never appear in `toString`, exceptions or logs.
- **Edits are checked.** Saving a route with an unknown destination or a malformed mention is refused with a field error.

## Other channels

Implement `ChannelSender` for email, Slack or a queue:

```kotlin
class MySender : ChannelSender {
    override val destinations = setOf("ops")
    override suspend fun send(destination: String, message: NotifyMessage) { /* ... */ }
    override fun mentionProblem(mention: String): String? = null
}
```

`Notifier` and `ResourceNotifySettings` also work without the Ktor plugin if you build them yourself from a `DataSource`
and the routes `Resource`.

## Good to know

- The routes resource has a name of its own: `notifyRoutes` by default, or `basekt.notify.resource` / `notifyRoutes("name", ...)`.
- Routes are looked up on every send, one indexed query. Add a cache in a `NotifySettings` of your own if events are very frequent.
- Messages are plain text for now. Embeds and buttons are not part of this module.
