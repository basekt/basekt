# Building and releasing

## Building

JDK 17 or later, and Node 22 or later for the admin panel's web build. Node is only needed to build BaseKt: the
panel ships prebuilt in `basekt-admin-ui`'s jar.

```bash
./gradlew build
```

### The S3 store against a real bucket

The S3 store's contract suite runs against an in-process fake that checks every request's signature. To also run it
against a real S3-compatible store, pass a bucket it may write to (each run uses a fresh prefix):

```bash
./gradlew :server:basekt-storage-s3:test -Pbasekt.s3.endpoint=http://localhost:9000 -Pbasekt.s3.bucket=basekt -Pbasekt.s3.accessKey=... -Pbasekt.s3.secretKey=...
```

### The Exposed adapter on Postgres

The Exposed adapter's tests run on H2. To also run its contract suite on Postgres, pass a database it may create
tables in:

```bash
./gradlew :server:basekt-data-exposed:test -Pbasekt.postgres.url=jdbc:postgresql://localhost/basekt -Pbasekt.postgres.user=basekt -Pbasekt.postgres.password=basekt
```

### The admin panel

The admin panel's source is in `admin/basekt-admin-ui/web`. To work on it with hot reload against a running server
(the sample shop on port 8080 by default, or `BASEKT_SERVER`), run `npm ci` and `npm run dev` there and open
`http://localhost:5173/admin/`.

### Admin API TypeScript types

After changing the admin API's wire classes, regenerate the TypeScript types and commit them. The panel compiles
against them, so a change it doesn't handle fails its build:

```bash
./gradlew :admin:basekt-admin-api:generateTypeScript
```

### This site

The docs are built with [Zensical](https://zensical.org) from `zensical.toml` and the `docs/` folder. To preview them:

```bash
pip install zensical
zensical serve
```

Pushes to `main` build and deploy them to GitHub Pages.

## Releasing

Every library module is published to Maven Central as `io.github.basekt:<module>:<version>`. The sample shop isn't
published. Every push to `main` publishes the libraries as a `-SNAPSHOT` (see
[Getting started](getting-started.md#snapshots)).

To cut a release, make sure `main` is green, then tag the commit and push the tag:

```bash
git tag -a v0.1.0 -m v0.1.0
git push origin v0.1.0
```

The **Release** workflow builds and tests, publishes every module to Maven Central, releases the deployment, and
creates the GitHub release with generated notes. To try publishing locally, with no credentials and no signing key:

```bash
./gradlew publishToMavenLocal -Pbasekt.version=0.1.0-local
```

[RELEASING.md](https://github.com/basekt/basekt/blob/main/RELEASING.md) has the details and the one-time setup.

## Contributing

Read [AGENTS.md](https://github.com/basekt/basekt/blob/main/AGENTS.md) for the design rules every module follows, and
copy its checklist into each PR.
