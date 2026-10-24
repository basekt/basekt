# Getting started

## Installing

Once published, every module is on Maven Central as `io.github.basekt:<module>:<version>`:

```kotlin
dependencies {
    implementation("io.github.basekt:basekt-server:0.1.0")
    implementation("io.github.basekt:basekt-data-exposed:0.1.0")   // or basekt-data-memory
    implementation("io.github.basekt:basekt-auth:0.1.0")
    implementation("io.github.basekt:basekt-admin-api:0.1.0")
    implementation("io.github.basekt:basekt-admin-ui:0.2.0")      // from 0.2.0
    testImplementation("io.github.basekt:basekt-testing:0.1.0")
}
```

You add only the modules you use. [Modules](modules.md) lists them all.

## Snapshots

The latest `main` is published as `0.3.0-SNAPSHOT` to Maven Central's snapshots repository
(`https://central.sonatype.com/repository/maven-snapshots/`). Snapshots aren't signed, are replaced by each push, and
expire after a while, so they're for trying the latest `main`, not for pinning. To use one:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://central.sonatype.com/repository/maven-snapshots/") { mavenContent { snapshotsOnly() } }
    }
}
// build.gradle.kts
dependencies { implementation("io.github.basekt:basekt-server:0.3.0-SNAPSHOT") }
```

Gradle re-checks a snapshot every 24 hours; `--refresh-dependencies` forces it.

## A first server

Install `BaseKt` with a data source, your resources and, if you want them, hooks, then add the admin API and panel.
The [home page](index.md) shows a whole `Application.module()`. The base path and page sizes come from the `basekt`
section of `ApplicationConfig`. From there, [Schema](schema.md) covers the fields, relations and routes, and
[Permissions](permissions.md) covers who may do what.

## The sample shop

The runnable example is [`samples/sample-shop`](https://github.com/basekt/basekt/tree/main/samples/sample-shop):
Exposed on H2, JWT and demo bearer tokens, a "price can't drop below cost" hook, product images on local disk, the
admin API, and the admin panel with an indigo theme set in Kotlin. Run:

```bash
./gradlew :samples:sample-shop:run
```

Then try `curl localhost:8080/api/products`, or open `http://localhost:8080/admin` and sign in with `staff-token`
(everything) or `customer-token` (browse, place orders).
