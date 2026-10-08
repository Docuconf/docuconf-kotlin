#!/usr/bin/env bash
# What the README tells a first-time user to run, in this project. CI runs it.
set -euo pipefail
cd "$(dirname "$0")"
# The committed contract.cue is current: docuconfCheck compares everything but
# metadata.generator.version, which release PRs bump. (It runs again in `check`
# below, after the export has rewritten the file.)
./gradlew docuconfCheck
./gradlew docuconfExport
./gradlew check
./gradlew installDist
set +e
env -u JAVA_TOOL_OPTIONS PORT=0 build/install/consumer/bin/consumer > build/bad-start.txt 2>&1
status=$?
set -e
cat build/bad-start.txt
[ "$status" -eq 1 ]
diff -u expected-bad-start.txt build/bad-start.txt
