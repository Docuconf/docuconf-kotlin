# Releasing

Two artifacts go to Maven Central through the [Central Portal](https://central.sonatype.com):
`dev.docuconf:docuconf-kotlin-core` (Kotlin Multiplatform: the root module plus
`docuconf-kotlin-core-jvm`) and `dev.docuconf:docuconf-hoplite`. Publishing uses the
[vanniktech maven-publish plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/central/),
which uploads to the Central Portal and signs with an in-memory GPG key.

Maven Central has no OIDC trusted publishing, so the release workflow uses a Portal user token and a
signing key stored as GitHub secrets.

## Before the first release

1. **Add a licence.** The licence is still pending. Central rejects POMs without `<licenses>`: add
   `POM_LICENCE_NAME`, `POM_LICENCE_URL` and `POM_LICENCE_DIST=repo` to `gradle.properties` and a
   `LICENSE` file when it is decided.
2. **Verify the namespace.** In the Central Portal, register and verify the `dev.docuconf` namespace
   (a DNS TXT record on `docuconf.dev`). Other docuconf SDKs (Java) publish under the same group;
   this one uses distinct artifact IDs.
3. **Create a Portal user token** (Account, Generate User Token). It has a username and a password.
4. **Create a signing key** used only for releases and publish its public half:

   ```
   gpg --quick-generate-key "docuconf releases <releases@docuconf.dev>" ed25519 sign 2y
   gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>
   gpg --export-secret-keys --armor <KEY_ID>      # the value of SIGNING_KEY
   ```

5. **Add the secrets** to a GitHub environment named `maven-central` (Settings, Environments), ideally
   with required reviewers so a tag alone cannot publish:

   | Secret | Value |
   |---|---|
   | `MAVEN_CENTRAL_USERNAME` | Portal token username |
   | `MAVEN_CENTRAL_PASSWORD` | Portal token password |
   | `SIGNING_KEY` | ASCII-armoured private key (`gpg --export-secret-keys --armor`) |
   | `SIGNING_KEY_ID` | The last 8 hex digits of the key ID |
   | `SIGNING_KEY_PASSWORD` | The key's passphrase |

   The workflow passes them to Gradle as `ORG_GRADLE_PROJECT_mavenCentralUsername`,
   `ORG_GRADLE_PROJECT_mavenCentralPassword`, `ORG_GRADLE_PROJECT_signingInMemoryKey`,
   `ORG_GRADLE_PROJECT_signingInMemoryKeyId` and `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`.
   Signing is only enabled when `signingInMemoryKey` is set, so local `publishToMavenLocal` works
   without a key.

## Each release

1. Set `VERSION_NAME` in `gradle.properties` and `Docuconf.VERSION` in
   `docuconf-hoplite/src/main/kotlin/dev/docuconf/hoplite/Docuconf.kt` to the new version (a test
   fails if they differ). Update the golden contract (`UPDATE_GOLDEN=1`), since it records the version.
2. Run `./gradlew check publishToMavenLocal` and try the artifacts from `~/.m2` in a sample app.
3. Commit, tag and push: `git tag v0.1.0 && git push origin v0.1.0`.
4. `.github/workflows/release.yml` checks the tag against `VERSION_NAME`, runs the tests (including
   `cue vet` against `docuconf/docuconf-go`'s meta-schema) and runs `publishToMavenCentral`.
5. In the Central Portal, open Deployments, check the files and signatures, and press **Publish**. To
   release without this step, change the workflow to `publishAndReleaseToMavenCentral`.

A deployment that fails validation can be dropped in the Portal (or with
`./gradlew dropMavenCentralDeployment`) and the tag re-pushed after fixing it.
