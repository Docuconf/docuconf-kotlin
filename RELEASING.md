# Releasing

Two artifacts go to Maven Central through the [Central Portal](https://central.sonatype.com):
`dev.docuconf:docuconf-kotlin-core` (Kotlin Multiplatform: the root module plus
`docuconf-kotlin-core-jvm`) and `dev.docuconf:docuconf-hoplite`. Publishing uses the
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

## GitHub Packages and Releases

The `github` job in `.github/workflows/release.yml` runs on the same `v*` tags. It repeats the tag check and
`./gradlew check`, then:

- runs `./gradlew publishAllPublicationsToGitHubPackagesRepository`, which publishes `dev.docuconf:docuconf-kotlin-core`
  (with `docuconf-kotlin-core-jvm`) and `dev.docuconf:docuconf-hoplite`, with sources and javadoc jars, to
  `https://maven.pkg.github.com/Docuconf/docuconf-kotlin`. They are not GPG-signed (no `signingInMemoryKey` is set).
  The `GitHubPackages` repository is added to every publishing module in the root `build.gradle.kts`;
- creates the GitHub Release for the tag if it does not exist, and attaches the JVM jars of both artifacts, with their
  sources and javadoc jars.

It does not depend on the Maven Central `publish` job, so it works before the Central Portal namespace, token, signing
key and `maven-central` environment exist. Gradle reads the credentials from
`ORG_GRADLE_PROJECT_GitHubPackagesUsername` / `ORG_GRADLE_PROJECT_GitHubPackagesPassword`, which the workflow sets to
the actor and its own `GITHUB_TOKEN` (`packages: write`, `contents: write`); there are no secrets or accounts to set
up. The only requirement is that the `Docuconf` organization lets `GITHUB_TOKEN` write packages, which it does unless
package creation has been restricted under Organization settings > Packages. Other Gradle tasks never need these
credentials.

### Installing from GitHub Packages

GitHub's Maven registry requires a token even for public packages. Create a personal access token (classic) with the
`read:packages` scope. With Gradle, in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://maven.pkg.github.com/Docuconf/docuconf-kotlin") {
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull ?: "YOUR_GITHUB_USERNAME"
                password = providers.environmentVariable("GITHUB_TOKEN").get()
            }
        }
    }
}
```

then depend on `dev.docuconf:docuconf-hoplite:0.1.0` as usual. With Maven, add a server to `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>github-docuconf</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>${env.GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
```

and the repository, with the same `<id>`, to the `pom.xml`:

```xml
<repositories>
  <repository>
    <id>github-docuconf</id>
    <url>https://maven.pkg.github.com/Docuconf/docuconf-kotlin</url>
  </repository>
</repositories>
```

Without a token, download the jars from the GitHub Release and add them as file dependencies
(`implementation(files("libs/docuconf-hoplite-0.1.0.jar", "libs/docuconf-kotlin-core-jvm-0.1.0.jar"))`, plus Hoplite
itself from Maven Central).

## docuconf-go version

docuconf-go owns the spec, the CUE meta-schema (`spec/cue`), the conformance suite (`conformance/cases.json`) and the
`docuconf` CLI. This SDK is tested against one docuconf-go commit, pinned in `.github/docuconf-go.ref` (a full SHA).

- **CI** checks out that commit on pushes and pull requests. The nightly scheduled run uses docuconf-go `main` instead,
  so a spec change that breaks this SDK shows up within a day. To try another docuconf-go commit or branch, run the CI
  workflow by hand (Actions, CI, Run workflow) with `docuconf_go_ref` set. Releases always build against the pinned commit.
- **Bump PRs.** `.github/workflows/docuconf-go-bump.yml` opens (or updates) a `build(deps): bump docuconf-go to <sha>`
  pull request from the `docuconf-go-bump` branch whenever docuconf-go `main` moves: immediately when docuconf-go sends
  a `docuconf-go-updated` dispatch (this needs the release GitHub App), otherwise on its daily schedule. CI on that PR
  is the compatibility check; merge it when it is green, or fix the SDK on the same branch. It can also be run by hand
  with a specific `sha`.
- **`scripts/conformance.sh`** runs only the docuconf-go-facing checks (the conformance suite and the `cue vet` of
  exported contracts) against any checkout: `DOCUCONF_GO_DIR=../docuconf-go scripts/conformance.sh`. CI runs it, and
  so does docuconf-go's downstream workflow, which runs it against every docuconf-go pull request that touches the spec,
  the conformance suite or the CLI. It needs JDK 17+ and `cue` on `PATH`, and sets this build's `DOCUCONF_SPEC_DIR` and `DOCUCONF_REQUIRE_CUE` from the contract's `DOCUCONF_SPEC_CUE` and `DOCUCONF_REQUIRE_VET`.

Without the release App (secrets `RELEASE_APP_ID` and `RELEASE_APP_PRIVATE_KEY`) the bump workflow uses
`GITHUB_TOKEN`: the repository setting "Allow GitHub Actions to create and approve pull requests" must be on, and
because a PR opened that way triggers no workflows, the bump workflow starts CI on the branch itself
(`workflow_dispatch`, whose checks show on the PR).
