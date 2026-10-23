# Releasing BaseKt

Every library module is published to Maven Central as `io.github.basekt:<module>:<version>`. The sample shop isn't
published. Publishing uses the [vanniktech maven-publish](https://vanniktech.github.io/gradle-maven-publish-plugin/)
plugin through the `basekt.publish` convention in `build-logic`.

## Cutting a release

1. Make sure `main` is green.
2. Tag the commit and push the tag:

   ```bash
   git tag -a v0.1.0 -m v0.1.0
   git push origin v0.1.0
   ```

3. The **Release** workflow builds and tests, publishes every module to Maven Central, releases the deployment, and
   creates the GitHub release with generated notes. A tag like `v0.2.0-rc.1` is published the same way and marked as
   a pre-release on GitHub.
4. Artifacts reach `repo1.maven.org` within about 30 minutes of the workflow finishing.

The version comes from the tag. `gradle.properties` keeps `basekt.version` at the next `-SNAPSHOT` for local builds.
A version can't be published to Maven Central twice. If a release fails after the upload, fix it and tag the next
patch version.

## Trying it locally

```bash
./gradlew publishToMavenLocal -Pbasekt.version=0.1.0-local
```

This needs no credentials and no signing key; publications are signed only when a key is present.

## One-time setup

These are done once by an owner of the `basekt` GitHub organization.

| Step | Where | What |
|---|---|---|
| 1. Namespace | [central.sonatype.com](https://central.sonatype.com) → Namespaces | Register `io.github.basekt`. Verify it by creating a public repository in the `basekt` org named with the verification key Central shows, then delete that repository once the namespace says *Verified*. |
| 2. Publishing token | central.sonatype.com → Account → Generate User Token | Store its username and password as repository secrets `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD`. |
| 3. Signing key | your machine (skip if you already have one, such as the key another project signs its releases with) | `gpg --quick-gen-key "BaseKt Releases <...>" rsa4096 sign 0`, then publish the public key: `gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>`. Central checks signatures against public keyservers. |
| 4. Signing secrets | GitHub → basekt/basekt → Settings → Secrets → Actions | `GPG_SIGNING_KEY`: the output of `gpg --armor --export-secret-keys <KEY_ID>`. `GPG_SIGNING_PASSWORD`: its passphrase, or leave it unset if the key has none. These are the same names awakekt/awake uses, so the same key can be added to both. |

The workflow checks for `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` and `GPG_SIGNING_KEY` before it builds, and
fails with the names of any that are missing.
