---
title: Verification of the de1f48c23 restart batch
type: research
date: 2026-10-14
status: measured
---

# Verification of the de1f48c23 restart batch

Default restarted on de1f48c23 (reset). Probes ran in SCI as agent `verify-1014` (branch retired at the end) and in JVM
mode read-only. Times are the `:seon.repl/ms` and `:seon.repl/alloc-mb` of the probe form unless the row says
`seon.profile/measure`. No new red was found. One first broad run showed 36 reds; they were transient (see 5).

## 1. Value defs: pass

`(ns probe.v1)`, `(def k1 42)`, `(defn uses-k1 [] k1)`, `(defn other [] 1)` each answered a Var, with no "Unable to resolve
symbol". `(probe.v1/uses-k1)` = 42.

| Form | ms | alloc MB |
|---|---:|---:|
| `(def k1 42)` | 0 | 0.0 |
| `(defn uses-k1 ...)` | 22 | 20.8 |
| `(defn other ...)` | 19 | 20.8 |
| `(probe.v1/uses-k1)` | 3 | 0.0 |

The two defns carry the expected `:uncontracted` finding. The whole request cost 712.6 ms (first requests on a restarted JVM).

## 2. Render reuse: pass (at the unit level), model-context only trivially measured

`seon.context/model-context` needs a held task to have must-reads, and assigning a task needs a declared proof, so the
reuse was measured on `seon.context/current-state-ai` over 8 real resolved references (`seon.ref/references`: 5 functions,
2 schemas, 1 test), the path `must-read-ai` -> `seon.render/render-call`. Each probe used one database value.

| References | Call 1 | Call 2 | Call 3 | Equal output |
|---|---|---|---|---|
| Set A (rendered once before) | 9.01 ms / 8.4 MB / 8 reads | 7.11 ms / 7.4 MB / 8 reads | 9.5 ms / 7.4 MB | yes |
| Set B (fresh) | **54.4 ms / 75.9 MB / 18 reads** | **5.7 ms / 6.9 MB / 8 reads** | 5.15 ms / 6.9 MB / 8 reads | not compared |

A fresh render of 8 units costs 54.4 ms and the repeat 5.7 ms: about 10x in time, 11x in allocation. The note's target
(112.73 -> ~22 ms for 16 units, 0 reads) is met on time. **Gap:** the repeat still counts 8 reads (one per unit, the
evidence check), not 0.

`model-context` itself (JVM mode, branch database of `verify-1014`, the turn's request): three calls on one value took
31.35 / 14.96 / 13.55 ms (32.8 / 15.6 / 15.5 MB, 27 reads each) and the three prompts were `=`. That agent held no task,
so the prompt was 83 characters and the check proves determinism only for the empty block, not for the `:step` section
that held the stamped refusal. A task-bearing agent was not available (no live agent holds a task after the reset).

## 3. current-state-ai from a plain eval: pass, with a note

The reproduction of `seon.context-test/a-plain-eval-renders-each-must-read-from-its-world`: no map in the result carries
`:seon.error/type` (44 ms, 19.6 MB). Note: the synthetic must-read `x` with no block id still comes back with
a `seon.render.value/node-id` identity refusal map ("A rendered value root requires a caller-supplied block id") that has
`:seon.error/layer` and `:seon.error/at` but no `:seon.error/type`. The admission caps and time limit no longer fail. The
refusal is the fixture's missing id, the same as the test; the missing `:seon.error/type` on a refusal map is worth a task
for the errors cut.

## 4. my.program/search: pass

Warm, as a bare `(count (my.program/search ...))` form:

| Query | ms | alloc MB |
|---|---:|---:|
| `"merge"` | 5 / 4 | 6.4 / 6.5 |
| `:seon.message/id` | 5 | 6.4 |

Target ~6 ms and ~6 MB. Each returned 3 results. (A first call wrapped in a `def` cost 130 ms / 84.5 MB cold.)

## 5. Broad writer tier: no new red

`bin/seon check --test seon.db-test --test seon.turn.batch-test --test seon.cluster.merge-test --test seon.task-test --include-long`

| Run | Candidate | Result |
|---|---|---|
| HEAD, first | 950dd05e6 (+ tree) | 36 reds (158 executed, 850 pass, 14 fail, 28 error, 57 s) |
| 2e1851291 (before the batch) | 2e1851291 | 4 reds (156 executed, 1044 pass) |
| HEAD, second | 8f9c604ce | **the same 4 reds** (158 executed, 1050 pass) |

The 4 reds, identical before the batch and at HEAD, are pre-existing:

- `seon.cluster.merge-test/a-require-in-the-agents-user-namespace-lands-with-its-change` (merge_test.clj:858)
- `seon.db-test/equal-declaration-content-shares-one-projection-across-values` (db_test.clj:2898)
- `seon.task-test/one-selector-serves-every-proof-consumer` (task_test.clj:389)
- `seon.turn.batch-test/a-contract-naming-a-predicate-defined-on-the-branch-is-admitted-and-armed` (batch_test.clj:287/289)

The first HEAD run's extra 32 reds (15 merge-test, 13 db-test, 4 batch-test) did not reproduce. Two shapes appeared:
`Node not found in storage` (address 6acef00b..., a commit of the store default had just collected: status showed a
collection completing at 03:01 and a GC lane's checks were running) and `Map literal must contain an even number of
forms` from `seon.test-support/reply` reading a cut reply. Three of them re-run alone at HEAD and at 35cafc1dc pass
(`merge-test/a-merge-conflict-reports-its-variant` -> 2 pass). The cause is a collection or contention racing the
throwaway JVM's seed, not a batch commit; a task for the check's seed against a concurrent collection is warranted.
Not bisected, because HEAD is green apart from the 4 pre-existing reds.

### Root's addition: `seon.cluster.armed-test/an-eval-request-after-a-collection-reads-no-collected-value`

| Where | Result |
|---|---|
| HEAD (8f9c604ce) alone | pass (3 assertions, 72 s, run 6ffecfb787d4) |
| 2e1851291 alone | pass (3 assertions, 65 s, run 0e7638709e0e) |

Red on neither, so there is nothing to bisect. It takes about 65-72 s, which is the cost of a throwaway JVM plus a
collection; if it was red on the GC lane's run, concurrent store work (the same cause as the first run's `node-not-found`)
is the candidate.

## 6. bin/seon status

`SEON status OK`, pid 10712, cluster `default`, health `:degraded`:

- Open faults (`seon.problems/open-faults`): none.
- Degraded by findings only: 4 `:seon.health/untested-hot-warning` (`seon.print/node-child?` 34,341 calls,
  `seon.cache/cache?` 18,258, `seon.profile/adder?` 13,548, `seon.cluster/cluster-name?` 11,094) and 2
  `:seon.problems/unowned-namespace` (`sentinel.scratch`, and my `probe.*` namespaces).
- Regressions 0, replaced roots 0, cache 73.8 of 512 MB, heap 1.19 of 4 GB, ready in 1,352 ms, 361 platform threads.
