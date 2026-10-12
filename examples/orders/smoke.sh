#!/usr/bin/env bash
# Smoke test for the orders example: a good start serves /healthz and a redacted /config, and a bad
# start exits non-zero with docuconf's error codes. Then the webhook key set: an empty key stops the
# boot without printing it, and mid-rotation a webhook signed with either key is accepted.
# Build first: ./gradlew :orders:installDist (from the repository root). Needs curl and openssl.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
app="${ORDERS_BIN:-$here/build/install/orders/bin/orders}"
# A free port, so the check never reaches another process.
port_in_use() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
port="${SMOKE_PORT:-$((20000 + RANDOM % 20000))}"
while port_in_use "$port"; do port=$((port + 1)); done
secret="postgres://orders:s3cret-pw@localhost:5432/orders"
# Two webhook keys: the old one and, mid-rotation, the new one.
old_key='old-webhook-key-0123456789abcdef0123'
new_key='new-webhook-key-0123456789abcdef0123'
log="$(mktemp)"
trap 'kill "${pid:-}" 2>/dev/null || true; rm -f "$log"' EXIT

fail() { echo "FAIL: $*" >&2; cat "$log" >&2; exit 1; }

wait_for_healthz() {
  for _ in $(seq 1 60); do
    curl -fsS "http://127.0.0.1:$port/healthz" >/dev/null 2>&1 && break
    kill -0 "$pid" 2>/dev/null || fail "the app exited during startup"
    sleep 0.5
  done
}

echo "== valid env"
env -i PATH="$PATH" JAVA_HOME="${JAVA_HOME:-}" PORT="$port" DATABASE_URL="$secret" WEBHOOK_KEYS="$old_key,$new_key" "$app" >"$log" 2>&1 &
pid=$!
wait_for_healthz
health="$(curl -fsS "http://127.0.0.1:$port/healthz")" || fail "GET /healthz failed"
[ "$health" = "ok" ] || fail "GET /healthz returned '$health'"
config="$(curl -fsS "http://127.0.0.1:$port/config")" || fail "GET /config failed"
echo "GET /config: $config"
case "$config" in *s3cret* | *webhook-key*) fail "GET /config leaks a secret" ;; esac
if grep -q -e s3cret -e webhook-key "$log"; then fail "the log shows a secret"; fi
case "$config" in *'"DATABASE_URL":"***"'*) ;; *) fail "GET /config does not redact DATABASE_URL" ;; esac
case "$config" in *'"WEBHOOK_KEYS":"***"'*) ;; *) fail "GET /config does not redact WEBHOOK_KEYS" ;; esac
code="$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'X-Signature: 00' -d '{}' "http://127.0.0.1:$port/webhooks/payments")"
[ "$code" = 401 ] || fail "an unsigned webhook got $code, want 401"
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

echo "== lowercase LOG_LEVEL and an ISO 8601 REQUEST_TIMEOUT"
env -i PATH="$PATH" JAVA_HOME="${JAVA_HOME:-}" PORT="$port" DATABASE_URL="$secret" LOG_LEVEL=debug REQUEST_TIMEOUT=PT1M30S "$app" >"$log" 2>&1 &
pid=$!
wait_for_healthz
config="$(curl -fsS "http://127.0.0.1:$port/config")" || fail "GET /config failed"
case "$config" in *'"LOG_LEVEL":"debug"'*'"REQUEST_TIMEOUT":"1m30s"'*) ;; *) fail "unexpected /config: $config" ;; esac
if grep -q sealed "$log"; then fail "Hoplite's sealed-type notice was printed"; fi
kill "$pid"; wait "$pid" 2>/dev/null || true; pid=

echo "== a key set with an empty second key (a trailing comma)"
set +e
env -i PATH="$PATH" JAVA_HOME="${JAVA_HOME:-}" PORT="$port" DATABASE_URL="$secret" WEBHOOK_KEYS="$old_key," DOCUCONF_TERMINATION_LOG=/dev/null "$app" >"$log" 2>&1
status=$?
set -e
cat "$log"
[ "$status" -eq 1 ] || fail "the app exited $status, not 1"
grep -qx 'docuconf: 1 configuration problem:' "$log" || fail "not exactly one problem"
grep -qx '  WEBHOOK_KEYS: out_of_range: key 2 is empty' "$log" || fail "no out_of_range for WEBHOOK_KEYS"
if grep -q webhook-key "$log"; then fail "the output shows a key"; fi

echo "== mid-rotation: either key is accepted, any other rejected"
env -i PATH="$PATH" JAVA_HOME="${JAVA_HOME:-}" PORT="$port" DATABASE_URL="$secret" WEBHOOK_KEYS="$old_key,$new_key" "$app" >"$log" 2>&1 &
pid=$!
wait_for_healthz
body='{"order":"42","status":"paid"}'
for key in "$old_key" "$new_key" "other-webhook-key-0123456789abcdef"; do
  sig="$(printf '%s' "$body" | openssl dgst -sha256 -hmac "$key" | sed 's/.*= //')"
  code="$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "X-Signature: $sig" -d "$body" "http://127.0.0.1:$port/webhooks/payments")"
  want=204; [ "${key#other}" != "$key" ] && want=401
  [ "$code" = "$want" ] || fail "webhook signed with the ${key%%-*} key got $code, want $want"
done
echo "webhooks: old and new key accepted, any other rejected"
kill "$pid"; wait "$pid" 2>/dev/null || true; pid=

echo "smoke: ok"
