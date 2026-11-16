# shop: roles, requiredIf and fragments

A tiny program, one image with two roles, that shows three parts of the contract end to end in
contract-first mode: roles ([SPEC §4.9](https://github.com/Docuconf/docuconf-go/blob/main/spec/SPEC.md)),
conditional requirements (`requiredIf`, SPEC §4.2.1) and an included well-known fragment (`otel@1`,
SPEC §4.8). There is no server, database or queue: it checks its configuration, prints one line and
exits.

The contract is written by hand: [`contract.json`](contract.json) is the canonical shop contract
every docuconf SDK's shop example uses, checked in as is. The Kotlin SDK has no declaration API for
roles, `requiredIf` or fragments (only the contract-first mode reads them), so there is no config
class to export from. The build packages `contract.json`, and at startup
[`Main.kt`](src/main/kotlin/dev/docuconf/examples/shop/Main.kt) checks the environment against it
with `Docuconf.checkContract` (`ContractFirst` plus file inputs under `DOCUCONF_FILE_ROOT`).
[`ShopConfig.kt`](src/main/kotlin/dev/docuconf/examples/shop/ShopConfig.kt) turns the checked
values into a typed `ShopConfig.Web` or `ShopConfig.Worker`.

| Input | Type | Rules | Roles |
|---|---|---|---|
| `DOCUCONF_ROLE` | enum | required; `web` or `worker` | |
| `DATABASE_URL` | url | required, secret; scheme `postgres` or `postgresql` | |
| `PORT` | int | 1–65535, default `8080` | `web` |
| `ALLOWED_ORIGINS` | list of strings (csv) | required | `web` |
| `QUEUES` | list of strings (csv) | required | `worker` |
| `WEBHOOKS_ENABLED` | bool | default `false` | `worker` |
| `LOG_FORMAT` | enum | `text` or `json`, default `text` | |
| `LOG_SCHEMA_URL` | url | scheme `https`; required when `LOG_FORMAT` is `json` | |
| file `webhook-signing-key` | text at `/etc/shop/webhooks/signing.key` | secret, at least 32 characters; required when `WEBHOOKS_ENABLED` is `true` | `worker` |
| `OTEL_*` (11 variables) | from the `otel@1` fragment | optional; `OTEL_EXPORTER_OTLP_PROTOCOL` is `grpc`, `http/protobuf` or `http/json` | |

- **Roles.** `DOCUCONF_ROLE` narrows the contract to the workload's role: an input whose `roles`
  leave the role out is optional, so the worker needs no `ALLOWED_ORIGINS` and the web role never
  needs the signing key, whatever `WEBHOOKS_ENABLED` says. With no valid role, the role is the only
  problem reported.
- **requiredIf.** `LOG_SCHEMA_URL` is required while `LOG_FORMAT` is `json`, and the signing key
  file while `WEBHOOKS_ENABLED` is `true`. The condition is on the loaded value, defaults included.
- **Fragments.** `includes: ["otel@1"]` names the fragment, and its variables are inline in the
  contract, so they are checked at boot like the app's own.

## Run it

From the repository root (the example builds against the SDK in this repository):

```console
$ ./gradlew :shop:installDist
$ DATABASE_URL=postgres://shop@db:5432/shop DOCUCONF_ROLE=web ALLOWED_ORIGINS=https://shop.example \
    examples/shop/build/install/shop/bin/shop
shop web: port 8080, origins https://shop.example
$ DATABASE_URL=postgres://shop@db:5432/shop DOCUCONF_ROLE=worker QUEUES=default,mail \
    examples/shop/build/install/shop/bin/shop
shop worker: queues default,mail, webhooks off
```

The worker's queues come from the typed config, not the raw variable. To turn webhooks on, put a
key of 32 or more characters at `/etc/shop/webhooks/signing.key` under `DOCUCONF_FILE_ROOT`:

```console
$ mkdir -p /tmp/shop/etc/shop/webhooks && openssl rand -hex 32 > /tmp/shop/etc/shop/webhooks/signing.key
$ DATABASE_URL=postgres://shop@db:5432/shop DOCUCONF_ROLE=worker QUEUES=default WEBHOOKS_ENABLED=true \
    DOCUCONF_FILE_ROOT=/tmp/shop examples/shop/build/install/shop/bin/shop
shop worker: queues default, webhooks on
```

A bad environment gets the SDK's standard startup failure: every problem listed on stderr (and in
the termination log), exit status 1, no secret values and no stack trace:

```console
$ DATABASE_URL=postgres://shop@db:5432/shop DOCUCONF_ROLE=worker QUEUES=default LOG_FORMAT=json \
    examples/shop/build/install/shop/bin/shop
docuconf: 1 configuration problem:
  LOG_SCHEMA_URL: missing_required: is required when LOG_FORMAT is "json"
```

## The test matrix

[`ShopTest`](src/test/kotlin/dev/docuconf/examples/shop/ShopTest.kt) runs the app's entry point
for each case, from the base environment `DATABASE_URL=postgres://shop@db:5432/shop` and a fresh,
empty `DOCUCONF_FILE_ROOT`, and checks the exit status, the output and, for a failure, that it is
the only problem (`./gradlew :shop:test`):

| # | Environment (plus the base) | Expected |
|---|---|---|
| 1 | `DOCUCONF_ROLE=web ALLOWED_ORIGINS=https://shop.example` | ok: `shop web: port 8080, origins https://shop.example` |
| 2 | `DOCUCONF_ROLE=worker QUEUES=default,mail` | ok: the worker needs no `ALLOWED_ORIGINS` |
| 3 | `DOCUCONF_ROLE=web` | `ALLOWED_ORIGINS` `missing_required` |
| 4 | `QUEUES=default` (no role) | `DOCUCONF_ROLE` `missing_required`, and nothing else |
| 5 | `DOCUCONF_ROLE=cron` | `DOCUCONF_ROLE` `not_in_enum`, and nothing else |
| 6 | `DOCUCONF_ROLE=worker QUEUES=default LOG_FORMAT=json` | `LOG_SCHEMA_URL` `missing_required`: `is required when LOG_FORMAT is "json"` |
| 7 | as 6, plus `LOG_SCHEMA_URL=https://logs.example/v1.json` | ok |
| 8 | `DOCUCONF_ROLE=worker QUEUES=default WEBHOOKS_ENABLED=true`, no key file | `webhook-signing-key` `file_missing`: `..., and is required when WEBHOOKS_ENABLED is true` |
| 9 | as 8, with a 32+ character key file | ok: `webhooks on` |
| 10 | `DOCUCONF_ROLE=web ALLOWED_ORIGINS=https://shop.example WEBHOOKS_ENABLED=true` | ok: the key file belongs to the worker |
| 11 | `DOCUCONF_ROLE=web ALLOWED_ORIGINS=https://shop.example OTEL_EXPORTER_OTLP_PROTOCOL=carrier-pigeon` | `OTEL_EXPORTER_OTLP_PROTOCOL` `not_in_enum`: fragment variables are checked at boot |

Lists are csv on the wire (the contract's default encoding). Two more tests check that the
worker's queues and signing key are typed values, and that a bad `DATABASE_URL` or a short key is
reported without its value. [`smoke.sh`](smoke.sh) runs the built app for cases 1, 2 and 6 and
checks stdout, stderr and the exit status; CI runs it.

## The contract and generated docs

`contract.json` is the source. [`contract.cue`](contract.cue) is the same contract in CUE, written
by [`gen-contract-cue.sh`](gen-contract-cue.sh) (`cue export contract.json --out cue`, wrapped in
`contract.#Contract`), for `cue vet` against the meta-schema and for the `docuconf` CLI. CI
regenerates it and fails when it changes, then vets it with `cue vet -c`.

[`CONFIG.md`](CONFIG.md), [`CONFIG.agents.md`](CONFIG.agents.md) and [`docs.json`](docs.json) are
generated from `contract.cue` by the `docuconf` CLI from
[docuconf-go](https://github.com/Docuconf/docuconf-go); never edit them by hand. After changing
`contract.json`:

```sh
cd examples/shop
./gen-contract-cue.sh
docuconf docs contract.cue -o CONFIG.md
docuconf docs contract.cue --format agents -o CONFIG.agents.md
docuconf docs contract.cue --format model -o docs.json
```

CI runs the same commands with `--check` and fails when a file is out of date.
