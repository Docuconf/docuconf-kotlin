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

Releases are automated with [release-please](https://github.com/googleapis/release-please); see
[CONTRIBUTING.md](CONTRIBUTING.md#how-releases-happen) for the commit conventions it reads.

1. Optionally, check out the open release PR (`chore(main): release X.Y.Z`), run
   `./gradlew check publishToMavenLocal` and try the artifacts from `~/.m2` in a sample app.
2. Merge the release PR. It already sets `VERSION_NAME` in `gradle.properties` and `Docuconf.VERSION` in
   `docuconf-hoplite/src/main/kotlin/dev/docuconf/hoplite/Docuconf.kt` to the new version, and updates
   `CHANGELOG.md`. The golden contract and the example contract do not need regenerating: their comparisons ignore
   `metadata.generator.version`.
3. release-please tags the merge commit `vX.Y.Z` and creates the GitHub release with the changelog entries.
4. `.github/workflows/release.yml` checks the tag against `VERSION_NAME`, runs the tests (including
   `cue vet` against `docuconf/docuconf-go`'s meta-schema) and runs `publishToMavenCentral`.
5. In the Central Portal, open Deployments, check the files and signatures, and press **Publish**. To
   release without this step, change the workflow to `publishAndReleaseToMavenCentral`.

If the release PR was created with `GITHUB_TOKEN` (no release GitHub App configured), the tag does not trigger
`release.yml` by itself, so `.github/workflows/release-please.yml` starts it with `gh workflow run`.

A deployment that fails validation can be dropped in the Portal (or with
`./gradlew dropMavenCentralDeployment`) and the release workflow re-run on the tag after fixing it
(`gh workflow run release.yml --ref vX.Y.Z`).

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
