# Discord account link

`AccountLinker` (`basekt-discord-link`) ties an account in your app to a Discord user with a one-time code. Your site
shows a code to a signed-in account's owner, the owner sends it to your bot, and the bot redeems it. There is no
Discord library in the module: it only decides whether a code may link a Discord user id to an account id, so you can
call it from any bot.

```kotlin
dependencies {
    implementation("io.github.basekt:basekt-discord-link:0.3.0-SNAPSHOT")
    testImplementation("io.github.basekt:basekt-discord-link-memory:0.3.0-SNAPSHOT")
}
```

## Use

```kotlin
val linker = AccountLinker(pending = myPendingLinkStore, accounts = myLinkedAccounts)

// On your site, for the signed-in account:
val code = linker.issue(accountId)          // "482913", valid for 24 hours by default

// In your bot's /verify command, with the sender's Discord user id:
when (val result = linker.redeem(code, discordUserId)) {
    is LinkResult.Linked -> reply("Linked to ${result.accountId}.")
    LinkResult.InvalidCode -> reply("That code is not valid.")
    LinkResult.Expired -> reply("That code expired. Ask for a new one on the site.")
    LinkResult.AccountAlreadyLinked -> reply("That account is already linked to another Discord user.")
    LinkResult.DiscordAlreadyLinked -> reply("This Discord user is already linked to another account.")
    LinkResult.TooManyAttempts -> reply("Too many wrong codes. Try again in a few minutes.")
}
```

## What it guarantees

- **Random codes.** Six digits from `SecureRandom`; a code another account holds is never reissued.
- **Single use.** `PendingLinkStore.take` is atomic, so two Discord users racing for one code cannot both win.
- **Expiry.** A code stops working after `ttl` (24 hours by default) and is spent when it is refused as expired.
- **One to one.** An account has at most one Discord user and a Discord user at most one account.
- **Guess limit.** After `maxFailures` wrong codes (5 by default) within `failureWindow` (10 minutes), a Discord user
  is refused until the window passes. The counts are kept in the `AccountLinker` instance's memory, so run one instance
  per process and add a limit of your own at the bot's edge if you run several.

## Storing links

Two small interfaces hold the state, so the module works with any database:

| Interface | Holds | Must be atomic |
|---|---|---|
| `PendingLinkStore` | codes issued and not yet redeemed | `take` (read and remove together) |
| `LinkedAccounts` | the finished account to Discord links, both directions | `link` (checks both sides and writes together) |

Back them with a table or collection in your own `DataSource`, with a unique index on the code, on the account id and
on the Discord user id. `basekt-discord-link-memory` has in-memory versions for tests and prototypes.

Run the contract suites from `basekt-testing` against your implementation:

```kotlin
class MyPendingLinkStoreTest : PendingLinkStoreContract() {
    override fun createPendingLinkStore(): PendingLinkStore = MyPendingLinkStore(freshDatabase())
}

class MyLinkedAccountsTest : LinkedAccountsContract() {
    override fun createLinkedAccounts(): LinkedAccounts = MyLinkedAccounts(freshDatabase())
}
```

## Good to know

- The module has no Ktor plugin yet. A later module will register the `/verify` command on the bot and call this for you.
- A code is kept as issued, not hashed. It is short lived and guarded by the attempt limit; do not log it.
- The module does not check that an account exists. Call `issue` only for accounts you have signed in.
