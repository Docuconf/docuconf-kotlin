# orders: a docuconf example service

A small HTTP service that declares its configuration as a Hoplite data class with docuconf
annotations ([`OrdersConfig.kt`](src/main/kotlin/dev/docuconf/examples/orders/OrdersConfig.kt)).
From that one class, docuconf exports [`contract.cue`](contract.cue) for the platform and checks
the environment at boot, reporting every problem at once. The server is the JDK's
`com.sun.net.httpserver.HttpServer`:

- `GET /healthz` returns `ok`;
- `GET /config` returns the loaded configuration as JSON, with `DATABASE_URL` shown as `"***"`.

| Variable | Type | Rules |
|---|---|---|
| `PORT` | int | 1–65535, default `8080` |
| `LOG_LEVEL` | enum | `debug`, `info`, `warn`, `error`; default `info` |
| `DATABASE_URL` | url | secret, required, scheme `postgres` |
| `ALLOWED_ORIGINS` | list of strings (csv) | at least 1 item; default `http://localhost:3000` |
| `REQUEST_TIMEOUT` | duration (ISO-8601, or one number and unit like `30s`) | 1s–5m, default `30s` |
| `WORKER_COUNT` | int | 1–64, default `4` |

Hoplite reads `_` as a nesting level, so `LOG_LEVEL` is the property `log.level`. That is why the
class nests small data classes instead of a flat `logLevel` (which would read `LOGLEVEL`).

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

With `PORT=0` and no `DATABASE_URL`, the app prints every problem and exits with status 1:

```console
$ PORT=0 examples/orders/build/install/orders/bin/orders
invalid configuration (2 problems):
  - PORT: out_of_range: "0" is below min 1
  - DATABASE_URL: missing_required: required, but not set
```

On Kubernetes the same text goes to `/dev/termination-log`, so `kubectl describe pod` shows it.

## Export the contract

```sh
./gradlew :orders:exportContract
```

This runs the SDK's export command (`dev.docuconf.hoplite.Export`, which calls
`Docuconf.exportCue()`) and rewrites `contract.cue`. Commit the result; CI fails when the committed
file differs from a fresh export.

## Deploy

The platform never runs this code to learn its configuration: it reads the committed
`contract.cue`. Before a deploy it checks the values for an environment with `docuconf vet` and
renders them into Kubernetes resources with `docuconf render` (from
[docuconf-go](https://github.com/Docuconf/docuconf-go)), or with the
[docuconf Helm chart](https://github.com/Docuconf/docuconf-go/tree/main/helm/docuconf). A missing
`DATABASE_URL` or an out-of-range `PORT` is then caught in the pipeline, and the boot check above
is the last line of defence.

[`smoke.sh`](smoke.sh) starts the built app with good and bad environments and checks both; CI
runs it.
