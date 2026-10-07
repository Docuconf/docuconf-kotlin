#!/usr/bin/env bash
# Runs only the docuconf-go-facing tests against a given docuconf-go checkout:
# the shared conformance suite (docuconf-kotlin-core's ConformanceTest) and the
# tests that `cue vet` exported contracts against its meta-schema
# (docuconf-hoplite's ExportTest and OverlayTest). Not the full suite.
#
#   DOCUCONF_GO_DIR=/path/to/docuconf-go scripts/conformance.sh
#
# Needs JDK 17+ and cue on PATH; ./gradlew fetches Gradle. docuconf-go's
# downstream workflow and this repository's CI both call it.
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
# --rerun: the conformance cases and the meta-schema live outside the build, so
# never trust an up-to-date test task.
./gradlew --no-daemon --stacktrace \
  :docuconf-kotlin-core:jvmTest --rerun --tests 'dev.docuconf.kotlin.core.ConformanceTest' \
  :docuconf-hoplite:test --rerun --tests 'dev.docuconf.hoplite.ExportTest' --tests 'dev.docuconf.hoplite.OverlayTest'
