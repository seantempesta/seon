---
type: research
status: current
tags: [research, performance, schema, run-loop, database, class/p1]
---

# Projection to the writer — what the turn actually pays, 2026-09-07

Landing note for step 1 of
[the agent record and the REPL response](../plan/agent-record-and-repl-response-prd-2026-09-07.md)
(§6 "Fix first", §7 step 1). Every number below was measured on an isolated
scratch cluster, `projection-lane` under `tmp/projection-lane-root`, on
2026-09-07. `juniper-context-live` and the repository default cluster were
never started, stopped, or written.

## Summary

**The premise of PRD §6 is falsified.** The run loop does NOT rebuild the
schema projection on its commits: measured over eight consecutive two-form
source turns, the turn thread derived the projection **zero times**, and the
loop half of the turn costs **76-117 ms**, already inside the PRD's 150 ms
target.

The 1.1-1.6 s a two-form source turn takes is paid **before the loop ever
runs**, on the thread that SUBMITS the source, and it is one cold projection
rebuild of 507-670 ms. Binding the cluster's projection state around the
submission alone takes the same turn from **728 ms to 147 ms wall**.

The disease named in
[the run loop unpacked](run-loop-unpacked-2026-09-07.md) §3.2/§3.3 is real
and is exactly AGENTS.md §2.1; only its location was wrong. §3.3 attributed
five `projection-from-database` calls to the loop on the strength of the
thread name `Clojure Connection seon.cluster/loop-research 7`. That is the
name of the MCP io-prepl session thread — the probe's OWN thread — not the
Datahike writer. Stack frames, not thread names, decide attribution.

## Method

```bash
unset SEON_OPERATOR_EPHEMERAL_OWNER_PID
mkdir -p tmp/projection-lane-root
bin/seon --root tmp/projection-lane-root init
bin/seon --root tmp/projection-lane-root start projection-lane
# probes through MCP eval_clj, root=tmp/projection-lane-root,
# cluster=projection-lane, mode=jvm; scripts in tmp/projection-lane/
bin/seon --root tmp/projection-lane-root down
```

Each trial submits `"(+ 1 1)\n(* 3 4)"` through
`seon.cluster.agent/submit-source!` with the live instance's own
`:seon.cluster.loop/cluster` handle and `:seon.cluster.agent/routing`, then
waits for `:seon.cluster.run/closed-at` on the returned run. A temporary
`alter-var-root` on `seon.schema/projection-from-database` (restored in a
`finally`) records each call's duration, its thread name, AND its stack; a
`datahike.api/listen` records per-commit `:tx-data` counts. The waiting poll
uses **`datahike.api/q`**, never `seon.db/q` — the first version of this probe
polled through `seon.db/q`, whose `read-declarations`
(`src/seon/db.clj:630`) rebuilds the projection on every newly committed
value, and that self-inflicted 500 ms landed inside the measured window.
Attribution is by stack frame; the earlier thread-name attribution is what
produced the wrong conclusion.

## The measurement

Four consecutive trials, submission unbound (today's production shape for
every caller that is not a flow proc):

| trial | wall ms | submit ms | loop ms | commits | projection rebuilds ON the turn thread |
|---|---|---|---|---|---|
| 1 | 728 | 644 | 84 | 3 | 0 |
| 2 | 693 | 599 | 94 | 3 | 0 |
| 3 | 814 | 697 | 117 | 3 | 0 |
| 4 | 756 | 654 | 102 | 3 | 0 |

`submit ms` is `submit-source!` itself; `loop ms` is from its return to the
run's `closed-at`. Every commit count is 3, every `:tx-data` count
`[38 9 4]` — identical to note 3 §2.

The same four trials with the submission wrapped in
`schema/call-with-projection-state` using the handle's own
`:seon.sci.eval/projection-state`:

| trial | wall ms | submit ms | loop ms | commits | projection rebuilds |
|---|---|---|---|---|---|
| 1 | 168 | 54 | 114 | 3 | 0 |
| 2 | 187 | 73 | 114 | 3 | 0 |
| 3 | 147 | 57 | 90 | 3 | 0 |
| 4 | 146 | 70 | 76 | 3 | 0 |

**728 → 147 ms wall on one binding.** The loop half does not move, because it
never paid.

The single expensive call in the unbound trials, by stack:

```
seon.db$read_declarations$fn.invoke(db.clj:631)      520 ms
seon.db$edn_encoded_QMARK_.invoke(db.clj:650)
seon.db$query_find_attributes.invoke(db.clj:889)
seon.db$decode_query_result.invoke(db.clj:938)
seon.db$q.doInvoke(db.clj:1158)
seon.cluster.agent$submit_source_BANG_.invoke(agent.clj)
```

It is a READ, not the write path: `seon.db/q`'s `read-declarations`
(`src/seon/db.clj:630`), not `transact-call` (`db.clj:2040-2057`).
`transact-call`'s own fallback measured **0 ms** in every turn trial, because
a read on the same thread has already warmed the identity cache for that
committed value.

## Why every commit misses the cache

`schema/projection-from-database` caches on
`datahike.db/committed-value-identity` (`src/seon/schema.clj:2425-2448`). A
commit mints a new identity, so the next unbound read or write on that
connection rebuilds. Measured cost of the miss, and of the miss when the
previously cached projection is offered to the two-argument arity as its
reusable projection:

| trial | cold rebuild ms | with reusable projection ms |
|---|---|---|
| 1 | 579 | 45 |
| 2 | 507 | 43 |
| 3 | 670 | 62 |
| 4 | 566 | 50 |

≈12×. The rebuild is not database work — note 3 §3.2 measured the three
queries at 2.46 / 1.56 / 2.83 ms — it is recompiling every declared Malli
schema and function contract.

## Why the turn is already clean, and why that was fragile

`seon.cluster/loop-handle` (`src/seon/cluster.clj:2484`) puts the cluster's
`:seon.sci.eval/projection-state` on the handle, and
`seon.cluster/projection-executor` (`cluster.clj:2519-2531`) wraps the process
`:io` executor so every `Runnable` it runs is inside
`schema/call-with-projection-state`. The agent graph passes that executor as
flow's `:io-exec` (`src/seon/cluster/agent.clj`), flow submits each proc's
whole run loop to it once
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:262,323`),
and the binding therefore holds for the life of the proc — including
`turn-step` and `seon.cluster.loop/turn`.

That is correct today and invisible tomorrow. The graph definition attaches
`:io-exec` under a `cond->` on `(:seon.flow/executor handle)`; a handle
without one silently loses the projection and every commit in the turn costs
half a second, with no signal at all. That is the failure class AGENTS.md
names: a check — here, an absent one — that reads absence as health.

## What landed

`seon.cluster.loop/turn` now binds the handle's own
`:seon.sci.eval/projection-state` around the whole pass, so the turn carries
its world at the transform that holds the handle rather than depending on
which executor happened to run the proc. A handle without projection state
leaves whatever the caller handed in place, so a fixture that binds its own
projection keeps it.

Proof: `a-turn-hands-its-clusters-projection-to-every-database-call`
(`test/seon/cluster/turn_test.clj`) runs `turn` on a **bare thread**, which
conveys no dynamic binding, and asserts zero
`schema/projection-from-database` calls plus completion inside
`seon.test-support/event-backstop-seconds`. On a bare thread the assertion is
honest: any projection the turn saw came from the handle. The test fails if
`turn` stops carrying it.

## Gate tallies

`bin/test seon.cluster.turn-test` at this commit: **59 tests, 426 assertions,
3 failures, 1 error**. The new regression passes. The same namespace at the
commit immediately before the `loop.clj` change ran in an isolated worktree:
**58 tests, 3 failures, 1 error** — the identical three tests, so all four
reds are pre-existing and none is caused by the binding:

- `a-run-prompts-from-its-opening-database-value` — the prompt renders
  `Renderer unavailable.` and echoes `(seon.db/pull …)` where the test expects
  `(my.message/read "m-1")`;
- `a-whole-turn-runs-a-REAL-sci-evaluation-end-to-end` — an ordinal-0
  evaluation carries `:seon.cluster.eval/read-basis-transaction` where the
  test expects none;
- `turn-intent-is-the-complete-crash-falsifier` — the deliberate cut surfaces
  as `IllegalArgumentException: Key must be integer` instead of the expected
  `cut during evaluation`.

All three sit in the prompt/evaluation-entity surface this PRD's steps 2-5
rewrite. They are reported, not fixed: they are outside this lane's paths.

`bin/test seon.db-test seon.cluster.loop-test`: **61 tests, 422 assertions,
7 failures, 2 errors**, all nine inside
`seon.db-test/unique-rejection-names-the-existing-owner-as-data`, which still
asserts a unique `:seon.cluster.agent/namespace` that `daf551e6c` deleted
([issue](../../../seon/issues/db-test-still-expects-a-unique-agent-namespace.md)).
`seon.cluster.loop-test` is green.

Live re-measurement after hot-reloading the changed `seon.cluster.loop` into
the running `projection-lane` JVM, submission bound, five trials: 229 / 179 /
171 / 170 / 198 ms wall (submit 96 / 64 / 65 / 59 / 82, loop 133 / 114 / 107 /
110 / 115), three commits each, zero projection rebuilds.

## What did NOT land, and why

**`transact-call` does not refuse when no projection is handed.** PRD §6 and
the lane assignment asked for a loud typed refusal there. The measurement
says that seam is never the cost in a turn (0 ms, always warmed by a read),
while a refusal would break every legitimate unbound writer: `bin/seon init`,
config apply, `eval_clj` in `jvm` mode, scripts, and the fixture load path,
which build the projection from packaged forms precisely because the database
has no schema rows yet (`db.clj:2052-2054`). Under the owner design gate this
is a decision, not a lane's to take.

**The submission path is not fixed.** `seon.cluster.agent/submit-source!`
lives in a protected path for this lane and is under concurrent edit by
another lane. The whole remaining 600 ms is there.

## Options for the owner, cheapest first

1. **Bind at the submission** — wrap `submit-source!`'s body in
   `schema/call-with-projection-state` with the handle's
   `:seon.sci.eval/projection-state`, exactly as `turn` now does. One value,
   one file. Measured: 728 → 147 ms wall, meeting PRD §8's ≤150 ms.
   Fixes only that caller.
2. **Reuse on a cache miss** — in `schema/projection-from-database`'s
   one-argument arity, when the identity cache misses, offer the most
   recently derived projection to `derive-projection-from-database` as its
   reusable projection. Measured: 507-670 ms → 43-62 ms for EVERY unbound
   caller (MCP `jvm` mode, scripts, the web threads, `bin/seon`), with no
   second cache and no contract change. Costs one guard: the reusable value
   may carry process-local predicate functions absent from its pure
   fingerprint (`schema.clj:2496-2500`), so the reused value must come from
   the database-derived cache itself, never from a caller.
3. **Refuse loudly in `seon.db`** — make both the read fallback
   (`db.clj:630`) and the write fallback (`db.clj:2047`) a typed
   `:seon.error` when nothing is handed, and give every legitimate unbound
   entry point an explicit binding. This is the AGENTS.md-correct end state
   and it is hours of cross-owner work; 1 and 2 do not block it.

Recommendation: 1 now (it is what step 1 promised and it hits the number),
then 2 (it dissolves the class for every caller), then 3 as a separate
ruled wave.

## Reproducing

Probe scripts are in `tmp/projection-lane/` (throwaway):
`measure2.clj` (unbound submission, per-thread attribution),
`measure3.clj` (splits submit from loop), `measure_bound.clj` (the
counterfactual), `reuse.clj` (cold rebuild versus reusable projection).
