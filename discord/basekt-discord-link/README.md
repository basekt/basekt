# basekt-discord-link

`AccountLinker` and the `PendingLinkStore` and `LinkedAccounts` SPIs: link an account to a Discord user with a one-time code, with no Ktor or Discord dependency.

```kotlin
dependencies { implementation("io.github.basekt:basekt-discord-link:0.3.0-SNAPSHOT") }
```

In the latest `main`, published as a snapshot until 0.3.0 is released: see [Getting started](https://basekt.github.io/basekt/getting-started/#snapshots).

## Use

Call `AccountLinker.issue(accountId)` on your site and show the code, then call `redeem(code, discordUserId)` from your bot's command. Implement the two store interfaces on your database and run `PendingLinkStoreContract` and `LinkedAccountsContract` from `basekt-testing`.

## Good to know

- Codes are random, single use and expiring, and a Discord user who sends too many wrong codes is refused for a while.
- An account links to at most one Discord user and a Discord user to at most one account.

## Documentation

- [Discord account link](https://basekt.github.io/basekt/discord-link/)
- [All modules](../../README.md#modules)
