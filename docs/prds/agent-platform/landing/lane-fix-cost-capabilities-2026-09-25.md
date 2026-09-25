---
type: report
status: measured
created: 2026-09-25
tags: [agent-platform, effect, db, performance]
---

# Lane fix-cost-capabilities-opus — landing (2026-09-25)

`seon.effect/capabilities` answered a root's capability reach with a recursive Datalog rule. Datahike
derived the whole `reachable` relation before binding `?root`, so each new database value cost about
3.3 s. It now walks stored `:seon.fn/calls` from the root with one `pull-many` per depth, the same walk
`render-program-evidence` uses, so the cost follows the root's reach.

## Broken or open first

1. **A runaway probe thread is running on default (pid 23282).** Thread "Clojure Connection
   seon.cluster/default 3" runs my scratch comparison. It calls the old `seon.effect/capabilities`
   once for each of the 4,929 function symbols, at about 3.3 s and 1.3 GB allocated per call. It
   ignores interrupts because Datahike's join never checks the flag, and JDK 26 has no `Thread.stop`.
   It ends when root adopts commit `1b9aafa96`. The loop calls through the Var, so the remaining
   calls then take about 2 ms each. A JVM restart also ends it, and one is already pending for the
   open `reference-code/sci` fault. **Adopt `1b9aafa96` or restart soon.** This was my error: the
   probe was not bounded.
2. **Defect 2 is stopped because the fix is in `src/seon/db.clj`, which another lane holds.**
   `turn/system-plan` only calls the owner. Nothing in turn.clj removes the cost. The design for
   db.clj's owner is below. No turn.clj edit was made.

## Defect 1: effect/capabilities (landed, commit 1b9aafa96)

The same default database had 4,929 functions and 65,705 call edges. The old timings are fresh
database values. The new timings come from the committed defn, evaluated as `capabilities-new` in the
scratch namespace `fix-cost-cap-scratch`. No Var in default was redefined.

| root | before (ms / MB alloc) | after (ms / MB alloc) |
|---|---|---|
| `clojure.core/inc` (leaf) | 3333 / 1289 | 0.032 / 0.07 |
| `seon.turn/turn` | 3338 / 1289 | 28 / 58 |
| `my.fs/read` (capability owner) | n/a | 6.9 / 15.5 |
| absent symbol | n/a | 0.03 / 0.07 |
| all 4,929 roots in sequence | about 4.5 h (estimated) | 10.8 s total, 2.2 ms mean, 50 ms max over a 300-root sample |

- **Semantics.** The result still includes the root if it is itself a capability. It also includes
  every reached row carrying `:seon.effect/capability`. Calls to symbols without rows reach nothing,
  and cycles terminate on the visited set. A refused `pull-many` (an error map) throws with the whole
  value as ex-data.
- **Equivalence check.** The walk agreed with the old query on `my.fs/read`. On default, no function
  reaches a capability other than itself, so the regression covers transitive reach in a constructed
  world.
- **No memo.** Walk cost is proportional to reach, at most about 50 ms, so no cache was added.
  Unchanged call graphs across database values do not need one.
- **Regression.** `seon.effect-test/capability-reachability-follows-the-roots-reach` renames
  `capability-reachability-is-a-database-query`. It checks four things:
  - a leaf on a fresh value takes less than 250 ms (it took 3 s before);
  - transitive reach works through `pure-caller`;
  - a self-call terminates (`indirect-caller`);
  - an absent root returns `#{}`.
- **Lines.** effect.clj +17/−24, so net −7. Deleted: the `reach-rules` Datalog rule (8 lines) and
  the rule query plus root pull (13 lines). Test +17/−2.

### Duplicate forward call-closure walks (not in my files; named, not edited)

Each of these walks `:seon.fn/calls` forward from a seed:

- `src/seon/render.clj:783-803` `render-program-evidence` uses `pull-many` per depth and returns the
  visited set (21 lines).
- `src/seon/db.clj:1413-1427` `builder-digest` uses `d/datoms` per symbol and returns digests of the
  reached symbols (15 lines).
- `src/seon/fn.clj:2288-2302` `reaches?` walks in-memory rows (15 lines).

`effect/capabilities` is now the same walk as `render-program-evidence`. One public walk returning
reached rows for a selector could serve all four. That cut spans render.clj, db.clj and fn.clj, and
it needs their owners. Root should record it as an issue; suggested path:
`docs/seon/issues/forward-call-closure-walk-duplicated-four-times.md`. Section 11 of the
duplicate-paths audit has no entry in this area.

## Defect 2: system-plan read-evidence-changes (not landed; db.clj design)

The probe was a JVM read with no writes: evaluation 88019 on default, with 480 retained reads and
basis 536878215, against a head at 536886175.

- `db/read-evidence-current?` takes 0.8 ms and returns false.
- `db/read-evidence-changes` takes **27,026 ms and allocates 31.6 GB** to return 14 witness datoms.
- On an evaluation with 4 reads it takes 1,700 ms and 1.77 GB.

Cause, verified: 73 of the 480 reads are stale. Of those, 50 have plan attributes `:all` and 8 have
none, so `read-evidence-changes` falls back to the pattern `[{}]`. That pattern is
`(d/datoms (d/since (d/history db) basis) :eavt)`, a scan of the whole store. Datahike's since view
materialises the index slice and then filters it by transaction
(`post-process-datoms` / `temporal-datom-filter`, reference-code/datahike/src/datahike/db.cljc:173-191).
The cost therefore follows the store's size, once per stale read and pattern. `changed-read-attributes`
returns nil for all 73 reads, because their revisions come from an earlier generation, so narrowing by
revision would not help here. `read-evidence-changes` also re-runs `read-evidence-current?` for each
read that system-plan has already classified as `:changed`. That duplicates the check: about 1 s of
the 480-read case.

Smallest composition at the owner, all in db.clj:

1. Build one `changes` view per basis for the whole call, not one for each read.
2. Materialise the since-history datoms **once** per distinct basis. Match every pattern of every
   stale read against that single sequence in memory, so the cost is one scan instead of 73.
3. Drop the inner `read-evidence-current?` re-check, because the caller already decided.

A real "since basis" cost would need a transaction-ordered source. Datahike keeps no transaction log
index, so it would have to be the `d/listen` tx-data of the transactions since the basis. That is a
larger decision for db.clj's owner.

## Root runs (serially, quiet window)

```
bin/test-check default --test seon.effect-test/capability-reachability-follows-the-roots-reach
bin/test-check default --ns seon.effect-test
bin/test-check default --ns seon.test.accretion-test
```

After adoption, repeat the probe:
`(time (seon.effect/capabilities (seon.db/db (seon.cluster.boot/connection "default")) 'clojure.core/inc))`.
Expect under 1 ms, not 3.3 s.

## Verification limits

- The lane ran no tests, per the load rule.
- clj-kondo reports 0 errors on effect.clj and effect_test.clj. The warnings are older.
- The committed defn was proved in a scratch namespace on real data. It was not loaded into default,
  because publication is paused.

RESET NEEDED: no. Default does need adoption of `1b9aafa96` or a restart, to end the runaway thread
in item 1.
