# Discord bot

`install(BaseKtDiscord)` (`basekt-discord`) runs a Discord bot beside your Ktor application. It connects when the
application starts, registers your slash commands, answers them, and disconnects when the application stops. It needs
no BaseKt schema, so a Ktor app that only wants a bot can install it alone.

```kotlin
dependencies { implementation("io.github.basekt:basekt-discord:0.3.0-SNAPSHOT") }
```

## Use

```kotlin
install(BaseKtDiscord) {
    // token = "..."   // or basekt.discord.token in the application's configuration
    command("verify", "Link your account") {
        stringOption("code", "The code from the site", required = true)
        handle { ctx ->
            ctx.reply("Checking code ${ctx.option("code")} for ${ctx.userId}")
        }
    }
}
```

Set the token in the application's configuration (`basekt.discord.token`) from your secret store. The library never
reads environment variables itself, never logs the token, and never keeps global state.

## Commands

- `command(name, description) { ... }` adds `/name`. Names are 1 to 32 lowercase letters, digits, `-` or `_`;
  descriptions are 1 to 100 characters.
- `stringOption` and `integerOption` add options. Required options come first.
- `ephemeral = true` (the default) makes an answer visible only to the user who ran the command; `false` makes it public.
- `handle { ctx -> ... }` receives a `CommandContext`: `ctx.userId`, `ctx.option("name")` and `ctx.reply(text)`.
  Call `reply` once. A handler that returns without replying gets a short "Done." answer, and one that throws gets a
  generic "Something went wrong" answer. The exception is logged, and its message never reaches Discord.
- The commands are registered as global commands with one call each start, so a command you remove disappears and none
  is registered twice. Discord can take a while to show new global commands.

## Rate limit

Each user may run 5 commands per 10 seconds by default. Change it with `rateLimit = RateLimit(max = 3, window = 30.seconds)`,
or turn it off with `rateLimit = null`. The counts are kept in this instance's memory and idle users are forgotten.

## Lifecycle and failures

- **Start.** The bot connects on `ApplicationStarted` in the application's own scope. If it can't connect (a wrong
  token, no network), the error is logged and the application keeps running.
- **Stop.** On `ApplicationStopping` the bot disconnects from its own thread, so a busy coroutine dispatcher can't
  deadlock shutdown, and the application waits at most `shutdownTimeout` (10 seconds by default).
- **Install.** Every configuration problem (no token, a bad or duplicate command name, a command with no `handle`) is
  reported at once, so the first start says everything to fix.

## Testing and other gateways

Discord sits behind the `DiscordGateway` interface. Pass your own with `gateway = ...` to test commands without a
connection, as `basekt-discord`'s own tests do, or to talk to Discord another way. The default gateway is built on
[Kord](https://github.com/kordlib/kord) and is internal to the module.

```kotlin
install(BaseKtDiscord) {
    gateway = myFakeGateway
    command("ping", "Pong") { handle { it.reply("pong") } }
}
```

## Good to know

- Only slash commands are supported. Message-content handlers, which need Discord's privileged intent, are not part of
  this module.
- A `/verify` command that links a Discord user to an account is built from this module and the account-link module
  (`basekt-discord-link`); a later release may wire the two together.
