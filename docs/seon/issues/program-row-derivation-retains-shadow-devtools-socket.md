---
type: issue
status: open
tags: [issue, build, cljs, flow]
severity: blocker
---

# Program-row derivation retains a Shadow devtools socket

## Evidence

The 2026-07-23 clientfix checkpoint removed the two first-party shell
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
