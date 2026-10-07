# Releasing

Three artifacts go to Maven Central through the [Central Portal](https://central.sonatype.com):
`dev.docuconf:docuconf-kotlin-core` (Kotlin Multiplatform: the root module plus
`docuconf-kotlin-core-jvm`), `dev.docuconf:docuconf-hoplite` and `dev.docuconf:docuconf-ktor`. The
`dev.docuconf` Gradle plugin (`docuconf-gradle-plugin/`, an included build) goes to the Gradle Plugin
Portal; its publishing (`com.gradle.plugin-publish` and a Portal key) is not set up yet. Publishing uses the
[vanniktech maven-publish plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/central/),
which uploads to the Central Portal and signs with an in-memory GPG key.

Maven Central has no OIDC trusted publishing, so the release workflow uses a Portal user token and a
signing key stored as GitHub secrets.

## Before the first release

The POM licence (MIT, `POM_LICENCE_*` in `gradle.properties`) and the `LICENSE` file are already in
place; Central rejects POMs without `<licenses>`.

1. **Verify the namespace.** In the Central Portal, register and verify the `dev.docuconf` namespace
   (a DNS TXT record on `docuconf.dev`). Other docuconf SDKs (Java) publish under the same group;
   this one uses distinct artifact IDs.
2. **Create a Portal user token** (Account, Generate User Token). It has a username and a password.
3. **Create a signing key** used only for releases and publish its public half:

   ```
   gpg --quick-generate-key "docuconf releases <releases@docuconf.dev>" ed25519 sign 2y
   gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>
   gpg --export-secret-keys --armor <KEY_ID>      # the value of SIGNING_KEY
   ```

4. **Add the secrets** to a GitHub environment named `maven-central` (Settings, Environments), ideally
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

1. Between releases `VERSION_NAME` is a `-SNAPSHOT` (`0.1.0-SNAPSHOT`), so `publishToMavenLocal`
   never writes a release version into anyone's `~/.m2`. For the release, set `VERSION_NAME` in
   `gradle.properties` and in `docuconf-gradle-plugin/gradle.properties` to the release version, and
   `Docuconf.VERSION` in `docuconf-hoplite/src/main/kotlin/dev/docuconf/hoplite/Docuconf.kt` to the
   same version without `-SNAPSHOT` (a test fails if they differ). Update the golden contract
   (`UPDATE_GOLDEN=1`) when the version changes, since it records it. Update the README's Install
   section to the registry coordinates. Afterwards, move `VERSION_NAME` to the next `-SNAPSHOT`.
2. Run `./gradlew check publishToMavenLocal` and try the artifacts from `~/.m2` in a sample app.
3. Commit, tag and push: `git tag v0.1.0 && git push origin v0.1.0`.
4. `.github/workflows/release.yml` checks the tag against `VERSION_NAME`, runs the tests (including
   `cue vet` against `docuconf/docuconf-go`'s meta-schema) and runs `publishToMavenCentral`.
5. In the Central Portal, open Deployments, check the files and signatures, and press **Publish**. To
   release without this step, change the workflow to `publishAndReleaseToMavenCentral`.

A deployment that fails validation can be dropped in the Portal (or with
`./gradlew dropMavenCentralDeployment`) and the tag re-pushed after fixing it.
