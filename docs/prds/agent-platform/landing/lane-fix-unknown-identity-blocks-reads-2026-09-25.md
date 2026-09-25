---
type: landing
status: landed-unverified (committed; publication paused, root adopts and runs the listed checks)
lane: fix-unknown-identity-blocks-reads (Opus 5.5)
created: 2026-09-25
source: root report 2026-09-25 ~00:50Z; landing lane-review-f3-builder-key-authority-2026-09-24.md item 5
---
# Landing: fix-unknown-identity-blocks-reads (2026-09-25)

`seon.db/loaded-builder` memoizes the loaded program's builder digest in
`projection-cache` under `[::builder commit]`; the fix makes a miss that cannot
materialize the commit cache nothing and key the read instead.

## Broken first

- Not yet proven in the installed JVM: hook publication is paused, so the
  `src/seon/db.clj` change is committed but not adopted. Root adopts, then runs
  the commands below.
- Root's hypothesis is **falsified** in part: both program-change tests
  already inject their own identity atom (cluster test lines 40–123, sci test
  lines 38–65). Neither reaches `seon.sci.eval/loaded-identity`. No test edit
  was needed for isolation. The regression now also asserts the JVM's identity
  is unchanged.

## Cause (verified at the REPL)

1. `loaded-builder`'s `memo` stored `(delay (builder-digest (or (d/commit-as-db
   database commit …) (throw …))))` via `cache/lookup-or-miss`. The key named
   only the commit. Whether it materializes depends on the *read value's
   store*.
2. Clojure 1.12.5 `Delay` keeps a thrown exception and rethrows it on every
   deref. Probe: a throwing delay derefed twice ran its body once and threw
   twice, `realized?` true.
3. `d/commit-as-db` on a memory-store value for default's loaded commit answers
   nil, but on default's own value it answers a database. Probe:
   `[nil true]`.
4. `projection-cache` is an LRU of 24 (db.clj:1257), shared with every
   projection. Builder entries are evicted under test churn. If the first miss
   afterwards is any value outside default's store (the memory-store tests:
   db_test, program_test, schema_in_place_test, turn_work_test, …) or a missing
   commit, it caches the throw. From then on every `carried-projection` read,
   status, test request and `init --dev` adoption refuses "The JVM's loaded
   source commit is unavailable." until the entry is evicted.
5. Reproduced in place. HEAD's `loaded-builder`, called once for an absent
   commit, poisoned that key. The fixed function's fast path then threw too
   (the key held a random uuid, so the harm is limited to that key, which the
   LRU evicts).

## Fix (src/seon/db.clj, `loaded-builder`)

- `memo` materializes before touching the cache. It caches only a delay over a
  materialized program and answers nil for a nil or absent commit. A failure is
  never stored.
- Known loaded commit or single boot commit: `(or (memo c) (digest
  [::foreign-store c own-builder]))`. The key is stable for each pair of loaded
  program and value rows, and is never shared with another program.
- Unknown identity whose target is absent: the existing `::mixed-program` key
  for that adoption. The value still gets a projection.
- The rule that differing boot commits refuse as ambiguous is unchanged. So is
  detection: members and evaluations reading unknown still answer the
  diagnostic (`call-detecting-program-change`).
- Status already reports an unknown identity (`cluster.clj:709`,
  `:seon.dev.mcp/program-unavailable`). No cluster.clj or eval.clj edit was
  needed.

## Evidence (scratch namespace `lane-fix-unknown-identity.probe`, new body as a local fn)

| case | result | ms |
|---|---|---|
| default value, loaded commit | same digest as HEAD (`14fefa8d…`) | 0.45 new (first call) / 0.03 HEAD |
| memory value, loaded commit | `14fefa8d…` (cached hit) | 0.007 |
| default value, absent commit | foreign-store digest | 9.1 (own-builder first compute) |
| unknown, absent target, default / memory | mixed digest `39248e8d…` | 5.5 / 0.25 |
| unknown, loaded target, default | `14fefa8d…` | 0.06 |
| absent commit cached afterwards | false | — |

The warm hit path is unchanged: a `cache/lookup` plus deref, as before.

## Regression (test/seon/cluster/program_change_test.clj)

In `an-adoption-failing-after-roots-change-keeps-the-program-unknown-until-one-records`:
- `loaded-builder` answers the same string twice for a commit absent from the
  test branch's store, both known and unknown (the unknown one produced by a
  real failed `call-with-program-change`). HEAD throws here.
- The JVM's `seon.sci.eval/loaded-identity` is unchanged across the test.

## Root runs (serially, after adopting this commit)

```
bin/test-check default --test seon.cluster.program-change-test/an-adoption-failing-after-roots-change-keeps-the-program-unknown-until-one-records
bin/test-check default --ns seon.cluster.program-change-test
bin/test-check default --ns seon.sci.program-change-test
bin/test-check default --ns seon.db-test
```

## Size and limits

- Net lines: src +18/−15 (docstring +4), test +13/−1.
- Commit: see `git log -- src/seon/db.clj`.
- Not run here (LOAD RULE): the test requests above, namespace load of seon.db
  (it would redefine default's Vars), and a live unknown-identity drill of MCP
  status and adoption. The status and adoption read paths reach this function
  through `carried-projection`, and the regression covers them only at the
  owner.
