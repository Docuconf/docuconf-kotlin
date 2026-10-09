# Security policy

## Reporting a vulnerability

Please report vulnerabilities privately, through GitHub's private vulnerability reporting: open the repository's
**Security** tab and choose **Report a vulnerability**
([direct link](https://github.com/docuconf/docuconf-kotlin/security/advisories/new)). Do not open a public issue, pull
request or discussion for a suspected vulnerability.

Include what you can of:

- the affected artifact and version (`docuconf-hoplite`, `docuconf-ktor`, `docuconf-kotlin-core` or the `dev.docuconf`
  Gradle plugin), and the Kotlin, JDK and Hoplite versions;
- what an attacker can do, and what they need first;
- steps or a minimal config class, contract or program that reproduces it.

We work on the fix in a private security advisory, credit you in it unless you prefer otherwise, and publish the
advisory when a fixed release is out.

## Response targets

| | |
|---|---|
| Acknowledge the report | within 3 business days |
| First assessment (confirmed or not, severity) | as soon as we can reproduce it, and we keep you updated in the advisory |
| Fix | released as a patch to the supported version, then the advisory is published |

## Supported versions

Every artifact in this repository is released together, with one version and one `v*` tag (see
[RELEASING.md](RELEASING.md)). Security fixes go to the latest minor release, as a new patch release:

| Artifact | Tag | Supported |
|---|---|---|
| `dev.docuconf:docuconf-hoplite`, `dev.docuconf:docuconf-ktor`, `dev.docuconf:docuconf-kotlin-core` | `v*` | latest minor |
| `dev.docuconf` Gradle plugin | `v*` | latest minor |

**During the beta, only the latest release is supported.** Upgrade to it to get a fix.

## Scope

In scope:

- the libraries in [`docuconf-kotlin-core`](docuconf-kotlin-core), [`docuconf-hoplite`](docuconf-hoplite) and
  [`docuconf-ktor`](docuconf-ktor), for example a value marked `secret` (or a key of a `KeySet`) that reaches an error
  message, a log line, `toString()` or the termination log, or a check that accepts a value or file it should reject;
- the Gradle plugin in [`docuconf-gradle-plugin`](docuconf-gradle-plugin).

Out of scope: the example applications under [`examples`](examples), vulnerabilities in dependencies that docuconf does
not make reachable (report those upstream, such as to Hoplite or Ktor), and issues in a platform or cluster that only
arise from its own misconfiguration. The docuconf CLI, the Go SDK, the CUE meta-schema and the Helm chart live in
[docuconf-go](https://github.com/docuconf/docuconf-go), and other language SDKs in their own repositories; each follows
its own policy.

Releases to Maven Central are signed; [RELEASING.md](RELEASING.md) describes how they are built and published.
