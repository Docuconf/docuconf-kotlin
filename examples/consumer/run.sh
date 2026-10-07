#!/usr/bin/env bash
# What the README tells a first-time user to run, in this project. CI runs it.
set -euo pipefail
cd "$(dirname "$0")"
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
git diff --exit-code -- contract.cue
