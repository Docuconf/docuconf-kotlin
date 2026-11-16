#!/usr/bin/env bash
# Smoke test for the shop example: runs the built app for the web role, the worker role and a worker
# with json logs but no LOG_SCHEMA_URL (a requiredIf), checking stdout, stderr and the exit status.
# Build first: ./gradlew :shop:installDist (from the repository root).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
app="${SHOP_BIN:-$here/build/install/shop/bin/shop}"
root="$(mktemp -d)" # an empty file root: no webhook signing key
out="$(mktemp)"
err="$(mktemp)"
trap 'rm -rf "$root" "$out" "$err"' EXIT

fail() { echo "FAIL: $*" >&2; echo "stdout:" >&2; cat "$out" >&2; echo "stderr:" >&2; cat "$err" >&2; exit 1; }

# Runs the app with the base environment plus "$@", with nothing else from this shell's environment.
shop() {
  set +e
  env -i PATH="$PATH" JAVA_HOME="${JAVA_HOME:-}" DOCUCONF_FILE_ROOT="$root" DOCUCONF_TERMINATION_LOG=/dev/null \
    DATABASE_URL=postgres://shop@db:5432/shop "$@" "$app" >"$out" 2>"$err"
  status=$?
  set -e
}

echo "== web"
shop DOCUCONF_ROLE=web ALLOWED_ORIGINS=https://shop.example
[ "$status" -eq 0 ] || fail "the app exited $status, not 0"
[ "$(cat "$out")" = "shop web: port 8080, origins https://shop.example" ] || fail "unexpected stdout"
cat "$out"

echo "== worker"
shop DOCUCONF_ROLE=worker QUEUES=default,mail
[ "$status" -eq 0 ] || fail "the app exited $status, not 0"
[ "$(cat "$out")" = "shop worker: queues default,mail, webhooks off" ] || fail "unexpected stdout"
cat "$out"

echo "== worker with LOG_FORMAT=json and no LOG_SCHEMA_URL"
shop DOCUCONF_ROLE=worker QUEUES=default LOG_FORMAT=json
cat "$err"
[ "$status" -eq 1 ] || fail "the app exited $status, not 1"
[ ! -s "$out" ] || fail "the app printed to stdout"
grep -qx 'docuconf: 1 configuration problem:' "$err" || fail "not exactly one problem"
grep -q '^  LOG_SCHEMA_URL: missing_required: .*is required when LOG_FORMAT is "json"' "$err" ||
  fail "no missing_required for LOG_SCHEMA_URL"
if grep -q -e Exception -e '^\s*at ' "$err"; then fail "the output has a stack trace"; fi

echo "smoke: ok"
