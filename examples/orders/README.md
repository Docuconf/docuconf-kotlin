# orders: a docuconf example service

A small HTTP service that declares its configuration as a Hoplite data class with docuconf
annotations ([`OrdersConfig.kt`](src/main/kotlin/dev/docuconf/examples/orders/OrdersConfig.kt)).
From that one class, docuconf exports [`contract.cue`](contract.cue) for the platform and checks
the environment at boot, reporting every problem at once. The server is the JDK's
`com.sun.net.httpserver.HttpServer`:

- `GET /healthz` returns `ok`;
- `GET /config` returns the loaded configuration as JSON, with `DATABASE_URL` and `WEBHOOK_KEYS`
  shown as `"***"`, set or not;
- `POST /webhooks/payments` accepts a webhook signed with any key in `WEBHOOK_KEYS` (see
  [Rotate a key](#rotate-a-key)).

| Variable | Type | Rules |
|---|---|---|
| `PORT` | int | 1–65535, default `8080` |
| `LOG_LEVEL` | enum | `debug`, `info`, `warn`, `error`; default `info` |
| `DATABASE_URL` | url | secret, required, scheme `postgres`, at most 2048 characters |
| `ALLOWED_ORIGINS` | list of strings (csv) | at least 1 item; default `http://localhost:3000` |
| `REQUEST_TIMEOUT` | duration (ISO 8601, like `PT30S`) | 1s–5m, default `30s` |
| `WORKER_COUNT` | int | 1–64, default `4` |
| `WEBHOOK_KEYS` | key set (csv), `KeySet` | secret, optional; 1–2 keys of 32–256 characters each |

Each property reads its name in SCREAMING_SNAKE_CASE (`logLevel` reads `LOG_LEVEL`), so the class
is one flat data class. `LogLevel` has idiomatic constants (`DEBUG`) with lowercase wire values
(`@WireName("debug")`). Durations are ISO 8601 on the wire (`PT1M30S`, what the platform renders),
and docuconf accepts exactly that grammar (SPEC §5): `1m30s` fails with `invalid_type`.

## Run it

From the repository root (the example builds against the SDK in this repository):

```sh
./gradlew :orders:installDist
DATABASE_URL=postgres://orders:secret@localhost:5432/orders \
ALLOWED_ORIGINS=https://shop.example.com,https://admin.example.com \
  examples/orders/build/install/orders/bin/orders
curl localhost:8080/config
```

## A bad start

With `PORT=0` and no `DATABASE_URL`, `Docuconf.loadOrExit` prints every problem and exits with
status 1, with no stack trace:

```console
$ PORT=0 examples/orders/build/install/orders/bin/orders
docuconf: 2 configuration problems:
  PORT: out_of_range: "0" is below min 1
  DATABASE_URL: missing_required: required, but not set
```

On Kubernetes the same text goes to `/dev/termination-log`, so `kubectl describe pod` shows it.

## Rotate a key

`WEBHOOK_KEYS` is a key set (`KeySet`, contract type `keySet`): `POST /webhooks/payments` accepts
a body whose `X-Signature` header is the hex HMAC-SHA256 of the body under any key in the set,
checked with `KeySet.verify`, which tries every key
([`Webhooks.kt`](src/main/kotlin/dev/docuconf/examples/orders/Webhooks.kt)). It is one
comma-separated variable, so one Kubernetes Secret key holds it:

```yaml
WEBHOOK_KEYS: # a key set: one Secret key holding "old,new" while rotating
  secretKeyRef: {name: orders-webhooks, key: keys}
```

A variable is read once, at start, so a new key reaches the service only when the pods restart;
with two keys valid at once, no webhook is turned away while that happens:

1. Add the new key as the second item (`old,new` in the Secret), and roll out.
2. Switch the sender to the new key.
3. Remove the old key (`new`), and roll out.

The contract allows 1 or 2 keys (a key set's default) of 32 to 256 characters each, so a trailing
comma or a truncated key stops the service at boot instead of locking out the sender, and the
message never shows a key:

```console
$ DATABASE_URL=postgres://orders:pw@localhost:5432/orders \
    WEBHOOK_KEYS=old-webhook-key-0123456789abcdef0123, examples/orders/build/install/orders/bin/orders
docuconf: 1 configuration problem:
  WEBHOOK_KEYS: out_of_range: key 1 is empty
```

[`WebhooksTest`](src/test/kotlin/dev/docuconf/examples/orders/WebhooksTest.kt) walks through a
rotation, and [`smoke.sh`](smoke.sh) posts webhooks signed with both keys.
[SPEC §6.1](https://github.com/Docuconf/docuconf-go/blob/main/spec/SPEC.md#61-rotation) covers
rotation in general.

## Export the contract

```sh
./gradlew :orders:docuconfExport
```

The `dev.docuconf` Gradle plugin runs the SDK's export command (`dev.docuconf.hoplite.Export`) and
rewrites `contract.cue`; the service name comes from `@DocuconfService(name = "orders")`. Commit the
result. `./gradlew :orders:docuconfCheck` (part of `check`, and run in CI) fails with a diff when the
committed file differs from a fresh export.

## Generated docs

[`CONFIG.md`](CONFIG.md), [`CONFIG.agents.md`](CONFIG.agents.md) and [`docs.json`](docs.json) are
generated from `contract.cue` by the `docuconf` CLI from
[docuconf-go](https://github.com/Docuconf/docuconf-go); never edit them by hand. The first is the
reference for developers, the second the rules and facts AI agents need to change the code or set
deployment values, and the third the docs model both are rendered from. Regenerate them after
exporting the contract:

```sh
cd examples/orders
docuconf docs contract.cue -o CONFIG.md
docuconf docs contract.cue --format agents -o CONFIG.agents.md
docuconf docs contract.cue --format model -o docs.json
```

CI runs the same commands with `--check` and fails when a file is out of date; it checks
[`../consumer`](../consumer)'s generated docs the same way. `WORKER_COUNT` shows where the text
comes from: the first sentence of its KDoc is the description, and the rest its details.
`WEBHOOK_KEYS` is a `keySet`, so the generated docs print its rotation steps themselves.

## Deploy

The platform never runs this code to learn its configuration: it reads the committed
`contract.cue`. Before a deploy it checks the values for an environment with `docuconf vet` and
renders them into Kubernetes resources with `docuconf render` (from
[docuconf-go](https://github.com/Docuconf/docuconf-go)), or with the
[docuconf Helm chart](https://github.com/Docuconf/docuconf-go/tree/main/helm/docuconf). A missing
`DATABASE_URL` or an out-of-range `PORT` is then caught in the pipeline, and the boot check above
is the last line of defence.

[`deploy/values.yaml`](deploy/values.yaml) is an environment's values, the key set among them as
a `secretKeyRef`; CI vets it against the contract.

[`smoke.sh`](smoke.sh) starts the built app with good and bad environments and checks both, and the
webhook key set; CI runs it.
