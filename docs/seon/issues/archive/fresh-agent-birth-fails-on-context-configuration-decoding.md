---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, config, runtime, archive]
---

# Fresh agent birth rejected stale program schema facts

## Problem

`POST /agents` could not create a fresh agent on the source-frozen default
cluster. The failure looked like drift between the database-expanded
configuration projection and the decoded singleton contract, but that
projection was correct. The live database had acquired stale program schema
facts from an older sidecar.

## Evidence

At HEAD `5f10e3d7f0aa8a02f013109e6ca8679199d9ed79`, with all five default
cluster processes ready, `POST /agents` returned HTTP 500:

```
{"seon.error/message":":malli.core/invalid-input",
 "seon.error/kind":"malli-instrument-input"}
```

The pod log identified the failing call as `seon.config/spawn-depth-cap` from
`seon.agent/acquire-spawn-database`. Its expanded context entities contained
the decoded vector form of `:seon.eval/home-requires`, while the acquired
runtime registry still declared that attribute as `:string`. Malli therefore
reported the whole singleton input as invalid. The same stale contract fault
was raised by `seon.error/with-configuration` during context rendering.

The exact HTTP evidence and log excerpt are appended to
`tmp/orchestrator/lifecycle-redrive-gate.log`.

## Root cause

Source already registered the correct union schema for
`:seon.eval/home-requires`. `out/client/main.js` had advanced while
`program-rows.edn` and `page-plan.edn` remained from an earlier flush. Every
program-artifact hook load had failed after its namespace acquired an
operator-only `babashka.fs` dependency through `seon.dev.config`; Shadow's
JavaScript build continued, leaving the stale sidecars available for later
artifact admission.

The first publication repair exposed two further owner contracts:

- hook sources were absent from the source artifact input digest, so the
  operator could still admit the old manifest after the hook repair; and
- source `cluster apply` started a watcher before selecting the resolved
  manifest, so the repaired hook had no admitted config identity.

## Resolution

The program-artifact hook and operator config now share one pure manifest
digest namespace that is available on Shadow's classpath. Hook and inventory
sources participate in source artifact identity. A source apply selects its
resolved manifest before starting the watcher, publishes and admits one
coherent client/program release, and retains that producing watcher for
subsequent startup. Watcher convergence derives only the watcher spec and does
not demand pod-only operational configuration.

The repair is committed as:

- `ceac85812` — keep config artifacts on the Shadow classpath;
- `a518fd91e` — fence Shadow hooks into artifact identity;
- `8b400521b`, `6af29e3ac`, and `e83334f5d` — publish, retain, and identify
  source releases with their producing watcher; and
- `ae63dae91` — select source configuration before the apply build.

No singleton schema was widened and instrumentation remains enabled.

## Proof

Default release
`04659bfefb552b511c01481ec9d09bf361506888621f748299349fe3812eb42a`
applied successfully. `bin/seon status` then reported watcher, writer, host,
pod, and web-render alive.

The public birth request returned HTTP 200 and agent id
`open-fans-wish`. `GET /agent/open-fans-wish` returned HTTP 200, and the
server-side `/agent/open-fans-wish/feed` emitted a complete
`datastar-patch-elements` frame containing that agent's idle header and
canvas.

Focused proof:

- CLJS portable config/agent selection: 11 tests, 150 assertions, zero
  failures or errors;
- JVM portable config/agent selection: 6 tests, 99 assertions, zero failures
  or errors;
- operator cluster/CLI selection: 59 tests, 220 assertions, zero failures or
  errors; and
- broader artifact/config operator selection: 48 tests, 270 assertions, zero
  failures or errors.

## Acceptance

- Fresh agent birth and the agent page now pass on the live default cluster.
- The decoded singleton satisfies the unchanged instrumented contract.
- The full multi-turn lifecycle gate remains the next lane's work; it was not
  driven as part of this blocker repair.
