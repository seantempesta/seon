---
type: issue
status: resolved
severity: blocker
tags: [issue, render, database, datahike]
---

# A booted cluster's block derivation returns one block where the facts have four

## Problem

Boot seeds root's four blocks, and a booted cluster then reported EITHER four
child blocks or one. When it reported one, the root page served with only its
header surface and no problems, agents, or messages — and gave no sign that
three were missing.

No fixture reproduced it. Every suite exercising this path built a fresh
in-memory Datahike inside the test and consistently saw four; the divergence
appeared only against a cluster booted through `seon.cluster/start!` on a file
store.

## Root cause — Datahike's query planner, in our vendored fork

The seeding transaction was never at fault. Ten consecutive fresh boots all
commit four `:seon.cluster.agent/blocks` datoms, and every index agrees:
`:eavt` 4, `:aevt` 4, `d/pull` 4, the relation find 4. Only
`seon.render.block/blocks` answered 1 — deterministically, on all ten
(`tmp/block_divergence_loop.clj`).

`reference-code/datahike/src/datahike/query/plan.cljc`, `build-pipeline`: an
entity-group's fused execution path was chosen with

```clojure
has-card-many? (boolean (some #(not (get-in % [:schema-info :card-one?] true))
                              merge-ops))
```

which asks only about the MERGE ops. It never asked about the SCAN. When
`has-card-many?` is false the group takes `:sorted-merge`
(`query/execute.cljc`, `execute-sorted-merge` / `sorted-merge-inner-loop`),
which walks each merge attribute with ONE forward
`PersistentSortedSet$ForwardCursor` across the entire scan. That is sound only
while the scan visits every entity once — a forward cursor cannot seek
backwards.

A cardinality-MANY scan attribute emits several datoms for the SAME entity. For
`[?agent :seon.cluster.agent/blocks ?block]` the `:aevt` slice is four datoms
all with `e` = the root agent. The first probes `(596 :seon.cluster.agent/id)`
and matches; the cursor then advances; the remaining three probe a key it has
already passed and are silently dropped. Four rows become one, with no error.

Costing puts the card-many pattern in the scan position whenever it looks
cheaper, which is why this is data-dependent and why no in-memory fixture ever
saw it: with a small store the unique `:seon.cluster.agent/id` pattern wins the
scan and the group takes the correct `:card-many-merge` path.

Traced live (`tmp/block_trace.clj`), before the fix:

```text
scan= [?agent :seon.cluster.agent/blocks ?block]  fused= :sorted-merge      -> 1
scan= [?a :seon.cluster.agent/id "root"]          fused= :card-many-merge   -> 4
```

## Fix

`build-pipeline` now derives the scan's own cardinality and excludes
`:sorted-merge` for a cardinality-many scan; such groups take
`:per-cursor-merge`, which re-probes per scan datom.

Falsifier-first, both sides:

- fork: `reference-code/datahike/test/datahike/test/query_planner_test.clj`,
  `test-card-many-scan-emits-every-value` — Seon-free, in-memory, deterministic
  (200 extra `:kind` datoms make the card-many pattern the cheaper scan).
  Without the fix: 4 failures, planner `#{[2]}` vs legacy `#{[2] [3] [4] [5]}`.
  With it: 31 tests / 130 assertions / 0 failures.
- Seon: `test/seon/cluster/boot-test`, `boots-seeded-blocks-are-all-derivable`
  — BOOTS a real cluster on a real file store and asserts `block/blocks`
  agrees, one for one, with a direct relation query and with the raw `:eavt`
  datoms over the same database value. Without the fork fix that suite is
  3 failures; with it, 0.
- `bin/test`: 386 tests / 1502 assertions / 0 failures (baseline 385/1497/0).
- Ten fresh boots now report four through every read, `block/blocks` included.

`seon.render.block/blocks` keeps the relation-then-pull-per-id shape; the
comment there blaming collection-find is now wrong and is removed — every find
form was equally affected, and the form was never the variable.

## Wrong readings recorded, because each cost real time

- **"The same query returns four inline and one inside the function."** False,
  and it came from comparing two different boots. Both agree in one process.
- **"Whatever is wrong happens at or before boot's seeding transaction."**
  Also false, and it sent the previous session after the transaction shape
  (nested maps, tempids, reverse refs — all tried, all irrelevant). The
  transaction always committed four. The right first move was the one the
  earlier note almost made: compare the committed datoms against the read,
  which takes one boot and immediately rules the writer out.
- **"It depends on the variable names."** A real observation with a misleading
  shape: only `?agent`+`?block` failed out of 81 name pairs, because that pair
  is what boot itself runs. It is not the names that matter — see the follow-on
  note below.
- Ruled out and still ruled out: stale var, AOT, instrumentation, the render
  pipeline, the query result cache, the parsed-query cache, the plan cache
  (all three cleared explicitly, none changed the answer).

## Notes

Found 2026-07-28 wiring root's seeded block set into boot (N4 package 2).
Probes retained: `tmp/block_divergence_loop.clj` (N boots, every read form),
`tmp/block_trace.clj` (chosen scan + fused path), `tmp/block_plan_dump.clj`,
`tmp/fork_falsifier_probe.clj` (the Seon-free minimal shape).

Three defects found in passing are in
`datahike-planner-and-caches-carry-three-smaller-defects.md`.
