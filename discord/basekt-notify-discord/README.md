# basekt-notify-discord

A `ChannelSender` that posts notifications to named Discord channel webhooks, with no bot and no gateway connection.

```kotlin
dependencies { implementation("io.github.basekt:basekt-notify-discord:0.3.0-SNAPSHOT") }
```

In the latest `main`, published as a snapshot until 0.3.0 is released: see [Getting started](https://basekt.github.io/basekt/getting-started/#snapshots).

Builds on: basekt-notify.

## Use

```kotlin
install(BaseKtNotify) {
    sender = DiscordWebhookSender(mapOf("announcements" to announcementsWebhookUrl))
}
```

## Good to know

- Every webhook URL is checked when the sender is built, and none appears in `toString`, an exception or a log line.
- `@everyone`, `@here` and user mentions in the text are switched off; only the role a route names is pinged.

## Documentation

- [Notifications](https://basekt.github.io/basekt/notify/)
- [All modules](../../README.md#modules)
