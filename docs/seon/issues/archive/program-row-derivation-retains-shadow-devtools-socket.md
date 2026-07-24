---
type: issue
status: resolved
tags: [issue, build, cljs, flow]
severity: blocker
---

# Program-row derivation retains a Shadow devtools socket

## Evidence

The 2026-07-23 clientfix checkpoint (`692bd252c`) removed the two first-party shell
config-conversion warnings and compiled both canonical `client` and `test`
builds cleanly. A one-shot `clj -M:cljs compile client` completed and published
`out/client/program-rows.edn`.

The required managed-watcher proof then timed out after 300 seconds. Its
temporary Bun process remained alive as:

```text
bun out/client/.program-rows-build-.../program-rows.js

```

`lsof` showed that process holding an established TCP connection to the live
Shadow server on port 9630. The generated derivation replaces only
`shadow.module.main.append`; the watch-configured Node output still carries the
devtools client, whose socket retains the process after the derivation writes
its result. The watcher therefore never completes the `:client` flush, and the
operator cannot atomically replace the stale v10 `artifact.edn` with v11.

Evidence is in `tmp/orchestrator/clientfix-gate.log` and
`logs/operator/watcher/147d1ecb-105f-49e0-8e1e-253b27a3d5e7.log`. The
interrupted operator-owned watcher was reaped with `bin/seon down`.

## Owner

`script/seon/dev/program_artifact.clj` owns the temporary program-row build and
its process lifetime. The repair must preserve the one compiled-indexer
derivation and one atomic artifact publisher.

## Acceptance

- A program-row derivation created from a managed watch build exits after
  emitting its marker and ordinary row vector.
- The watcher completes its first `client`, `execution`, and `test` flush.
- `bin/seon up` publishes artifact manifest v11 with
  `:seon.dev.artifact/program-row-path` and
  `:seon.dev.artifact/program-row-digest`.
- The published digest matches the exact `program-rows.edn` bytes.

## Resolution

The temporary derivation now applies Shadow's own
`:devtools {:enabled false}` configuration semantics to the copied build
state: it removes the injected `shadow.cljs.devtools.client.node` main entry
and reruns `shadow.build.api/analyze-modules` before flushing the unoptimized
Node program. The compiled client indexer remains the one row derivation, but
the generated program no longer contains the websocket-owning entry.

An isolated `s2fix` managed watcher reached readiness and published manifest
version 11. Its program-row path and digest were present, the published file's
SHA-256 matched
`524e2e001368e9be6b234a3f0403bff51e0804fc99cd2d6d3c9e2ab35af8109b`,
and no `.program-rows-build-*` Bun process remained. The operator was then
shut down. Evidence is retained in `tmp/orchestrator/s2fix-gate.log`.
