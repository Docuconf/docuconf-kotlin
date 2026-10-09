#!/usr/bin/env bash
# Runs only the docuconf-go-facing tests against a given docuconf-go checkout: the shared conformance
# suite (docuconf-hoplite's ConformanceTest, every case through the contract-first mode, failing if
# any case is skipped), the shared export fixture compared with conformance/export/golden.cue by
# `docuconf conformance export` (ConformanceExportTest), and the tests that `cue vet` exported
# contracts against its meta-schema (ExportTest and OverlayTest). Not the full suite.
#
#   DOCUCONF_GO_DIR=/path/to/docuconf-go scripts/conformance.sh
#
# Needs JDK 17+ and cue on PATH; ./gradlew fetches Gradle. The docuconf CLI is DOCUCONF_CLI, else
# `docuconf` on PATH, else built here from DOCUCONF_GO_DIR (which needs Go). docuconf-go's downstream
# workflow and this repository's CI both call it.
set -euo pipefail

: "${DOCUCONF_GO_DIR:?set DOCUCONF_GO_DIR to a docuconf-go checkout}"
DOCUCONF_GO_DIR="$(cd "$DOCUCONF_GO_DIR" && pwd)"
export DOCUCONF_GO_DIR
export DOCUCONF_CONFORMANCE="${DOCUCONF_CONFORMANCE:-$DOCUCONF_GO_DIR/conformance/cases.json}"
export DOCUCONF_SPEC_CUE="${DOCUCONF_SPEC_CUE:-$DOCUCONF_GO_DIR/spec/cue}"
export DOCUCONF_REQUIRE_CONFORMANCE=1
export DOCUCONF_REQUIRE_VET=1
# This SDK's own names for the last two.
export DOCUCONF_SPEC_DIR="$DOCUCONF_SPEC_CUE"
export DOCUCONF_REQUIRE_CUE=1

cd "$(dirname "$0")/.."
if [ -z "${DOCUCONF_CLI:-}" ]; then
  if command -v docuconf >/dev/null 2>&1; then
    DOCUCONF_CLI="$(command -v docuconf)"
  else
    DOCUCONF_CLI="$PWD/build/docuconf-cli/docuconf"
    mkdir -p "$(dirname "$DOCUCONF_CLI")"
    (cd "$DOCUCONF_GO_DIR/cmd/docuconf" && go build -o "$DOCUCONF_CLI" .)
  fi
fi
export DOCUCONF_CLI

# --rerun: the conformance cases and the meta-schema live outside the build, so
# never trust an up-to-date test task.
./gradlew --no-daemon --stacktrace \
  :docuconf-hoplite:test --rerun \
  --tests 'dev.docuconf.hoplite.ConformanceTest' --tests 'dev.docuconf.hoplite.ConformanceExportTest' \
  --tests 'dev.docuconf.hoplite.ExportTest' --tests 'dev.docuconf.hoplite.OverlayTest'
# The runner writes its count; it has already failed the build if any case was skipped.
cat docuconf-hoplite/build/conformance-summary.txt
