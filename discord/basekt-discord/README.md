# basekt-discord

`install(BaseKtDiscord)`: a Discord bot as a Ktor plugin, with slash commands, a per-user rate limit and a clean shutdown.

```kotlin
dependencies { implementation("io.github.basekt:basekt-discord:0.3.0-SNAPSHOT") }
```

In the latest `main`, published as a snapshot until 0.3.0 is released: see [Getting started](https://basekt.github.io/basekt/getting-started/#snapshots).

## Use

```kotlin
install(BaseKtDiscord) {
    command("ping", "Pong") { handle { it.reply("pong") } }
}
```

Set the token in the application's configuration (`basekt.discord.token`), or pass a `gateway` of your own for tests.

## Good to know

- Needs no BaseKt schema, and depends on Ktor and Kord only.
- An exception in a handler is logged and answered generically; each user is rate limited; a failed connection doesn't stop the application.

## Documentation

- [Discord bot](https://basekt.github.io/basekt/discord-bot/)
- [All modules](../../README.md#modules)
