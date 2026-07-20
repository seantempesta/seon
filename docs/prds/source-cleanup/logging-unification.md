---
type: prd
status: active
tags: [prd, agent, web]
---

# Logging unification PRD

## Owner ruling (2026-07-20)

JVM writer adopts the client's log line FORMAT only (no writer event file).

## Problems

Evidence: [[../database-authority-mesh/research/cleanup-audit-logging-errors-2026-07-20]].

1. **Agent-readable log blind to loop faults**: ~30 of 84 call sites print
   via `js/console.*` directly, so `seon.log/tail` and the rotated
   events file never see them; the worst cluster is `seon.agent.loop`
   (16 sites). *Fix lane in flight (B2).*
2. **`seon.eval` "record-eval! tx FAILED" (~line 3394)** may violate
   "nothing caught without becoming data" — verification in the B2 lane.
3. **Three line formats**: client structured console lines, timbre defaults
   on the writer, bare `[database]` printlns.
4. **Contained value→throw→value round-trips**: `agent/turn.cljs:622-627`,
   `931-933`, `ctx/canvas.cljs:342` re-throw an already-formed `:seon/error`
   map to reach an outer catch; `ctx/transcript.cljs:349` throws inside the
   guarded render walker.
5. **`logs/` is a midden**: hundreds of stray `pod-bench-*`/`pod-plan-*`
   probe logs and Inspect `.eval` files beside the real events log.

## Recommended solution

1. Land B2 (routes the residue through `seon.log`).
2. Timbre `output-fn` on the writer reproducing the `seon.log/console!`
   line shape — format only, destinations unchanged; delete the bare
   `[database]` printlns in favor of timbre.
3. Round-trips: keep behavior, replace throw-as-control-flow with the
   ordinary early-return value shape ONLY if the rewrite stays local to each
   function; otherwise leave with a comment naming the pattern. They do not
   violate the boundary contract (nothing escapes), so this is polish, not a
   bug row.
4. `logs/` hygiene: gitignore the stray patterns, move Inspect `.eval`
   output under `src-inspect-ai/evals/` (already ignored), prune once.

## Acceptance

Writer + CLJS suites; one log line from each process shows the same shape;
`seon.log/tail` shows a loop fault end-to-end; `logs/` contains only the
events log pair and operator logs after one full `bin/seon restart` +
Inspect smoke.

## Round-trip disposition (explained to owner 2026-07-20)

The sites throw an already-formed error VALUE purely as an internal
early-return between `run-turn!` pipeline steps; the outer catch converts
it back to the documented `{:seon.agent.turn/status :error}` value. Contract
is respected. Restructuring means refactoring the most load-bearing engine
function — deferred to each file's next natural touch; recorded here.
