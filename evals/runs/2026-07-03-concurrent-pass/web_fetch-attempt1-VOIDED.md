---
type: research
status: completed
tags: [research, agent]
---

# web_fetch re-run attempt 1 — VOIDED (frozen_bundle_changed)

All 24 executions (8 dev samples x k=3, N=2 concurrent frozen clusters)
completed, then `run_tool_row`'s end-of-run identity assertion raised
`cluster.FrozenBundleChanged` — no ledger row published.

## Identities

- start: sha256 `8c371085…` mtime 00:52:44 size 70776
- end:   sha256 `8c371085…` mtime 01:05:39 size 70776 (sha/size SAME, mtime moved)

## Root cause (two defects, both fixed in this unit)

1. **Per-create staleness rebuild**: `bin/seon cluster create` ran
   `ensure_bench_bundle fresh` — the tooling lane saved `src/seon/eval.cljs`
   at 01:05 (also analyzer_info.cljs 00:55, client.cljs 00:46), so a
   mid-run create rebuilt the bundle UNDER the run. Fix: creates are now
   presence-only; freshness is RUN-level (`bin/seon bench-bundle`, fired
   once by the harness before dispatch — run_tool_row/run_bench at every
   parallelism).
2. **Identity hole**: `main.js` is a ~70KB shadow loader; the compiled code
   lives in `.shadow-cljs/builds/bench-client/dev/out/cljs-runtime/*.js` —
   a rebuild left main.js byte-identical (sha USELESS; only mtime fired).
   Fix: `write_bench_sha` now hashes the loader + every runtime chunk
   (paths included). Proven: chunk tweak → sha `3398a3d8…`, restore →
   `1580d85a…` (stable).

The per-execution records were attached to the raise (`e.logs`) but the
driver script did not catch it — records lost; workspaces remain under
`tmp/eval-workspaces/web_fetch-2026-07-03/e{1,2,3}`. Detection worked
exactly as designed (contamination voided, never scored through); the
re-run (attempt 2) runs on the fixed pin.
