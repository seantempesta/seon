---
type: issue
status: resolved
tags: [issue, agent, flow, architecture]
severity: cleanup
---

# The context/render system has no clean-build (node-test) coverage

## Problem

`bin/test-cljs` (the `:node-test` shadow build, `:optimizations :simple`) cannot
exercise `assemble-context`, render-handler dispatch, or the capabilities section.
10 `agent_context_test` assertions fail with `<unresolved-section …>` output, and
a core.async crash in `db_test.cljs:366` kills the Node process before the
summary. **Both are PRE-EXISTING** (present since `5f2a564`, before the
coherent-bootstrap-indexing work) — verified by commit-bisect across test logs.

So our verification has been **live-pod-only**: the code works in the long-running
`:client` pod (`:optimizations :none`) but a clean build can't test it. That gap
is what let a wrong "Step 2 regression" theory circulate before the bisect
corrected it.

## Root causes

1. **`lookup-value` (`src/seon/eval.cljs:279-313`) walks `js/globalThis`** with
   munged JS paths (`globalThis.seon_agent…`). Under `:optimizations :none` every
   ns fn is a `goog.global` property → works. Under `:simple` Closure
   renames/inlines → `globalThis.seon_agent` is `undefined` → every section
   symbol resolves to nil → `assemble-context` falls through to `pretty-ai` →
   `<unresolved-section>`. Confirmed: `node -e "…typeof globalThis.seon_agent"` →
   `undefined` in `out/test/test.js`.
2. **`db_test.cljs:366`**: `(go (a/<! (db/transact! …)))` passes the ^:async
   Promise to `a/<!`; under `:simple` core.async's IOC machinery doesn't see the
   Promise as a `ReadPort` → `…ReadPort take! is not a function` → process crash
   (compiler even emits "unreachable code" warnings there).

## ⚠ Two conflicting root-cause claims — DISAMBIGUATE

Two agents diagnosed the 10 `agent_context_test` failures differently:
- **Verifier (bisect):** `lookup-value` walking `globalThis` (cause 1 above) — the
  `<unresolved-section>` symptom is section *fns* not resolving under `:simple`.
- **Step-3 completion agent:** a `binding [db/*conn* conn]`-across-`.then` bug in
  the test fixtures (`agent_context_test.cljs` `with-seeded-conn`, ~:136-139) —
  the CLJS `binding` doesn't survive the Promise `.then`, so seed data lands in
  the DEFAULT conn, not the test conn (the SAME bug it FIXED in `test_test.cljs`,
  which made those tests pass in node-test).

These are likely BOTH real but DIFFERENT failures (wrong-conn seed ≠
`<unresolved-section>`). Disambiguation test: apply the explicit-`:seon.db/conn`
fix to `agent_context_test`'s `with-seeded-conn` and re-run node-test — if
`<unresolved-section>` persists, cause 1 (`lookup-value`) stands; if the failures
clear, it was the binding bug. Resolve before claiming the node-test gap fixed.

## Acceptance criteria

- The context/render path is testable in a clean build. Either:
  (a) switch the `:node-test` build to `:optimizations :none`, OR
  (b) replace `lookup-value`'s `globalThis` walk with an explicit symbol→fn
      registry atom (populated on `def`/reload) that survives Closure renaming —
      the more robust fix, and removes a self-host fragility.
- Fix or quarantine the `db_test.cljs:366` `go`+Promise crash so the suite runs
  to a summary.
- A clean `bin/test-cljs` run reaches a pass/fail summary with the context tests
  green.

## Refs

- `src/seon/eval.cljs:279-313` (`lookup-value`), `test/seon/db_test.cljs:366`
- Surfaced bisecting the Step 3 node-test discrepancy (coherent-bootstrap-indexing).
- Process note: "verify in the live pod" (CLAUDE.md oracle) is necessary but not
  sufficient — corpus/render work needs a clean-build test path too.
