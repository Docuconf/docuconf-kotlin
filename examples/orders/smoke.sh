#!/usr/bin/env bash
# Smoke test for the orders example: a good start serves /healthz and a redacted /config, and a bad
# start exits non-zero with docuconf's error codes.
# Build first: ./gradlew :orders:installDist (from the repository root).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
app="${ORDERS_BIN:-$here/build/install/orders/bin/orders}"
# A free port, so the check never reaches another process.
port_in_use() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
port="${SMOKE_PORT:-$((20000 + RANDOM % 20000))}"
while port_in_use "$port"; do port=$((port + 1)); done
secret="postgres://orders:s3cret-pw@localhost:5432/orders"
log="$(mktemp)"
trap 'kill "${pid:-}" 2>/dev/null || true; rm -f "$log"' EXIT

fail() { echo "FAIL: $*" >&2; cat "$log" >&2; exit 1; }

echo "== valid env"
env -i PATH="$PATH" JAVA_HOME="${JAVA_HOME:-}" PORT="$port" DATABASE_URL="$secret" "$app" >"$log" 2>&1 &
pid=$!
for _ in $(seq 1 60); do
  curl -fsS "http://127.0.0.1:$port/healthz" >/dev/null 2>&1 && break
  kill -0 "$pid" 2>/dev/null || fail "the app exited during startup"
  sleep 0.5
done
health="$(curl -fsS "http://127.0.0.1:$port/healthz")" || fail "GET /healthz failed"
[ "$health" = "ok" ] || fail "GET /healthz returned '$health'"
config="$(curl -fsS "http://127.0.0.1:$port/config")" || fail "GET /config failed"
echo "GET /config: $config"
case "$config" in *s3cret*) fail "GET /config leaks the secret" ;; esac
case "$config" in *'"***"'*) ;; *) fail "GET /config does not show the redacted secret" ;; esac
kill "$pid"; wait "$pid" 2>/dev/null || true; pid=

echo "== PORT=0, no DATABASE_URL"
set +e
env -i PATH="$PATH" JAVA_HOME="${JAVA_HOME:-}" PORT=0 DOCUCONF_TERMINATION_LOG=/dev/null "$app" >"$log" 2>&1
status=$?
set -e
cat "$log"
[ "$status" -ne 0 ] || fail "the app exited 0"
grep -q missing_required "$log" || fail "no missing_required in the output"
grep -q out_of_range "$log" || fail "no out_of_range in the output"
grep -qx 'docuconf: 2 configuration problems:' "$log" || fail "no report header in the output"
if grep -q -e Exception -e '^\s*at ' "$log"; then fail "the output has a stack trace"; fi
[ "$status" -eq 1 ] || fail "the app exited $status, not 1"

echo "== lowercase LOG_LEVEL and Go-style REQUEST_TIMEOUT"
env -i PATH="$PATH" JAVA_HOME="${JAVA_HOME:-}" PORT="$port" DATABASE_URL="$secret" LOG_LEVEL=debug REQUEST_TIMEOUT=1m30s "$app" >"$log" 2>&1 &
pid=$!
for _ in $(seq 1 60); do
  curl -fsS "http://127.0.0.1:$port/healthz" >/dev/null 2>&1 && break
  kill -0 "$pid" 2>/dev/null || fail "the app exited during startup"
  sleep 0.5
done
config="$(curl -fsS "http://127.0.0.1:$port/config")" || fail "GET /config failed"
case "$config" in *'"LOG_LEVEL":"debug"'*'"REQUEST_TIMEOUT":"1m30s"'*) ;; *) fail "unexpected /config: $config" ;; esac
if grep -q sealed "$log"; then fail "Hoplite's sealed-type notice was printed"; fi
kill "$pid"; wait "$pid" 2>/dev/null || true; pid=

echo "smoke: ok"
