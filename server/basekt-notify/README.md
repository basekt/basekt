# basekt-notify

`install(BaseKtNotify)`: send an event to a channel that staff choose in the admin panel, through the `ChannelSender` SPI.

```kotlin
dependencies { implementation("io.github.basekt:basekt-notify:0.3.0-SNAPSHOT") }
```

In the latest `main`, published as a snapshot until 0.3.0 is released: see [Getting started](https://basekt.github.io/basekt/getting-started/#snapshots).

Builds on: basekt-server.

## Use

```kotlin
schema { notifyRoutes(editors = setOf("admin")) }
install(BaseKtNotify) { sender = mySender }
application.notifier.publish("enrolled", "Ana enrolled")
```

## Good to know

- Staff set `event`, `enabled`, `destination` and `mention` in the `notifyRoutes` resource; a change applies to the next event.
- `publish` never throws, and an event with no route sends nothing.
- Settings store a destination name, not a URL, so secrets stay in the sender's configuration.

## Documentation

- [Notifications](https://basekt.github.io/basekt/notify/)
- [All modules](../../README.md#modules)
