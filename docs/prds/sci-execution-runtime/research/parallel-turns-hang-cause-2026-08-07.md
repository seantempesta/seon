---
type: research
status: complete
tags: [research, runtime, testing]
---

# The bare-suite hang: cause, evidence, and fix — 2026-08-07

## Verdict, in three sentences

**The hang is not `n-agent-parallel-turns-property`.** Both retained
virtual-thread-aware dumps name
`seon.cluster.work-test/situation-totality-property`, and in the bare run that
was in flight during this investigation the parallel-turns property completed
in 19.8 s while work-test's property wedged. **It is not a deadlock**: `main`
is `RUNNABLE` in both dumps, burning CPU re-reading the classpath. **The cause
is the audited isolation defect I**, in its most expensive instantiation: with
no declaration population supplied on the calling thread,
`seon.schema/registered-schemas` falls through to
`seon.schema.edn/packaged-forms`, which re-reads and re-validates all 151
schema resources — and the Datahike encode seam called it **once per
attribute**.

Measured: a three-attribute transaction cost **43.4 ms** through the ambient
encode path against **0.004 ms** through the explicit-projection path that
already existed beside it. Ten thousand times.

## What I read, end to end

As instructed, and stated for the record:

- [plan/unsettled.md](../plan/unsettled.md) — the ELEGANCE SEQUENCE block
  (lines 45-70, which contains the hang attribution corrected here) and the
  LIVE DRIVE blocks (72-93 and the 2026-07-27 record at 2806);
- [parallel-isolation-audit-2026-08-07.md](parallel-isolation-audit-2026-08-07.md)
  — complete;
- [seon-env-prd-2026-08-07.md](../plan/seon-env-prd-2026-08-07.md) — complete,
  including the Phase 0 findings log and rulings 1-10.

Also read complete: [CLAUDE.md](../../../../CLAUDE.md),
[test/seon/test_support.clj](../../../../test/seon/test_support.clj),
[schema-environment-is-ambient-not-explicit](../../../seon/issues/schema-environment-is-ambient-not-explicit.md),
and the relevant spans of `src/seon/schema/datahike.clj`,
`src/seon/schema/edn.clj`, `src/seon/schema.clj`, `src/seon/db.clj`,
`test/seon/cluster/work_test.clj`, and `test/seon/cluster/agent_test.clj`.

## Correcting the attribution

`unsettled.md:63-66` records: "n-agent-parallel-turns-property hangs at the
300 s liveness backstop (first concurrency probe of the new seams; dumps
retained in tmp/test-runs/run.8KPLyX)". That directory no longer exists
(`bin/test` removes successful isolated roots and retains failed ones). Two
other retained roots DO hold liveness dumps, and both name a different test:

| Retained root | Selection | `last-progress` |
|---|---|---|
| `tmp/test-runs/run.jEV5qx` | `default tier` (bare) | `BEGIN test seon.cluster.work-test/situation-totality-property` |
| `tmp/test-runs/run.AWdxwi` | `seon.cluster.work-test` | `BEGIN test seon.cluster.work-test/situation-totality-property` |
| `tmp/test-runs/run.cHRqCf` | `default tier` (bare) | `BEGIN test seon.cluster.work-test/situation-totality-property` |

The second is decisive: it is a run whose selection was *only* work-test, so
the parallel-turns property was not even loaded for that wedge.

The third arrived DURING this investigation and is the cleanest evidence of
all. A bare `bin/test` at `e2a539d58` had loaded its namespaces at 20:34, before
the fix was written, so it ran the old code. It reached the property at
`20:57:57` and fired `SUITE LIVENESS BUG — no reporter progress for 300
seconds` at `21:02:57`, naming that property — while two runs of the FIXED
code completed the same property in 59.9 s and 61.9 s on the same machine at
the same time. Its dump caught `main` one frame deeper than the other two,
inside the actual disk read:

```
at [java.io.FileInputStream open0 FileInputStream.java -2]
at [clojure.java.io$reader invokeStatic io.clj 105]
at [seon.schema.edn$read_schema_resource invoke edn.clj 202]
at [seon.schema.edn$resource_population invoke edn.clj 315]
at [seon.schema.edn$packaged_forms invoke edn.clj 337]
at [seon.schema$registered_schemas invoke schema.clj 2287]
at [seon.schema.datahike$edn_encoded_attr_QMARK_ invoke datahike.clj 331]
at [seon.schema.datahike$encode_entity invoke datahike.clj 419]
```

Three dumps, three runs, one stack, ending in `FileInputStream.open0`.

Three independent confirmations that the parallel-turns property is healthy:

1. In the bare run in flight during this investigation
   (`tmp/test-runs/bare-run-1634.log:513-514`, unmodified code):
   `BEGIN … n-agent-parallel-turns-property` at `20:38:09.105`,
   `END` at `20:38:28.893` — 19.8 s, passed.
2. Run in isolation via `clojure -M:dev:test` with a 30 s watchdog dump: it
   completed before the first watchdog tick and no dump was taken.
3. `bin/test seon.cluster.agent-test` — 16 tests, 99 assertions, 0 failures,
   81 s wall including JVM load.

This matches the standing rule that an attribution is a hypothesis until a
probe confirms it. The hang was real; the name attached to it was not.

## The dumps

`tmp/test-runs/run.jEV5qx/tmp/test-liveness/49027-1786107816969.log`, with
`deadlocked-thread-ids nil` and `main` in state `RUNNABLE` — excerpted from
the bottom of the frame list upward, which is the causal chain:

```
at [seon.cluster.work_test$with_database invoke work_test.clj 79]
at [seon.db$transact_BANG_ invoke db.clj 1163]
at [seon.db$transact_call invoke db.clj 1014]
at [seon.schema.datahike$encode_transaction invoke datahike.clj 462]
at [seon.schema.datahike$encode_transaction_data invoke datahike.clj 444]
at [seon.schema.datahike$encode_entity invoke datahike.clj 419]
at [clojure.core$reduce_kv invokeStatic core.clj 7002]          ; <- per attribute
at [seon.schema.datahike$edn_encoded_attr_QMARK_ invoke datahike.clj 331]
at [seon.schema$registered_schemas invoke schema.clj 2287]
at [seon.schema$candidate_forms invoke schema.clj 590]
at [seon.schema$packaged_forms invoke schema.clj 587]
at [seon.schema.edn$packaged_forms invoke edn.clj 337]
at [seon.schema.edn$resource_population invoke edn.clj 315]
at [seon.schema.edn$merge_schema_resources invoke edn.clj 293]
at [clojure.core$merge invokeStatic core.clj 3076]
```

`tmp/test-runs/run.AWdxwi/tmp/test-liveness/54596-1786108189314.log` is the
identical chain caught one frame over, in the sibling half of
`resource-population`:

```
at [seon.schema.edn$resource_population invoke edn.clj 315]
at [seon.schema.edn$validate_resource_placement_BANG_ invoke edn.clj 250]
at [clojure.core$into invoke core.clj 7033]
```

Two samples, two different runs, the same stack. That is not a race; it is a
hot loop.

## The cause chain, file:line

1. `test/seon/cluster/work_test.clj:79-90` — a hand-built fixture that opens a
   bare in-memory store and supplies **no** declaration population. Legal: the
   ambient fallback exists precisely for this.
2. `src/seon/db.clj:1021` — `transact-call` invokes the ambient
   `schema.datahike/encode-transaction`.
3. `src/seon/schema/datahike.clj:419-425` (before this change) —
   `encode-entity` `reduce-kv`s the entity and calls `edn-encoded-attr?`
   **for every attribute**.
4. `src/seon/schema/datahike.clj:331-337` — `edn-encoded-attr?` builds a
   one-key projection map from `schema/registered-schemas` on each call.
5. `src/seon/schema.clj:2287-2292` → `candidate-forms` (`:590-599`) → with no
   overlay, no `*packaged-forms*`, no `*projection-state*`, and no
   `*projection*`, it falls through to `packaged-forms` (`:587`).
6. `src/seon/schema/edn.clj:337-341` → `resource-population` (`:315-326`),
   which enumerates the classpath schema resources, reads all 151 of them,
   `merge-schema-resources` (`:293`) merges 1,882 declarations, and
   `validate-resource-placement!` (`:250`) revalidates every key's placement.

Steps 4-6 run once per attribute, per entity, per transaction. The property
runs 200 `quick-check` trials, each opening a store and committing several
transactions, the first of which installs an 11-attribute schema.

## Measurement

`tmp/repro/packaged_forms_cost.clj`, `clojure -M:dev:repro`, median of 5:

```
declared forms: 1882
UNBOUND registered-schemas ms:          18.44
UNBOUND edn-encoded-attr? ms:           14.30
UNBOUND encode-transaction (3 attrs):   43.42     ; ~= 3 x 14.3
BOUND   encode-transaction-in (3 attrs): 0.004
```

The three-attribute cost being three times the single-attribute cost is the
per-attribute amplification stated as an arithmetic identity.

## Classification

Category **(a) — an audited isolation defect**, exactly as the owner's ruling 7
anticipated, though at a different test than the one named. It is the audit's
Defect I: "any carrier that fails to propagate it degrades SILENTLY to the
process-wide default". What this investigation adds to the audit is that the
degradation is not only semantically wrong — **it is catastrophically slow**,
because the process-wide default is not a cached value but a full classpath
re-read. The audit noted that `packaged-base-forms` and `!source-files` "are
gone in favor of the pure `resource-population`" and read that as neutral; it
is not neutral on the fallback path.

There is a category (c) component: the work-test fixture supplies no
environment. That is not a defect on its own — supplying nothing is a
supported calling shape — so the fix belongs in production, not in the test.
Fixing only the fixture would have masked the class.

## The fix

`src/seon/schema/datahike.clj`. The correct implementation **already existed
beside the broken one**: an explicit-projection `-in` family
(`encode-transaction-in`, `encode-transaction-data-in`, `encode-entity-in`,
`encode-value-in`, `edn-encoded-attr-in?`, `validate-logical-slot-in!`) that
threads one projection through the whole walk. The ambient family was a
duplicate of it that re-resolved per leaf.

So the change is a **deletion plus a delegation**, not an addition:
`encode-entity` and `encode-transaction-data` are deleted, and
`encode-transaction` resolves the declaration population **exactly once** and
hands it to `encode-transaction-in`.

This satisfies the sealed design without anticipating it: the PRD's rule for
derived state is "keyed by complete identity … and read exactly once", and
Phase 1 will replace the one remaining `registered-schemas` call with the
environment's `:seon.schema/projection` at a single site rather than at every
leaf. No second mechanism was built; one of two mechanisms was removed.

Deliberately NOT memoized. `resource-population` was made pure on purpose, and
a process-global cache of declaration facts is on the PRD's deletion list and
banned by its mutable-reference rule. The answer is to resolve once and pass
the value, which is what the design says.

## Proof

| Evidence | Before | After |
|---|---|---|
| `bin/test seon.cluster.work-test` | wedged; 300 s liveness backstop fired, run never finished (`run.AWdxwi`) | 10 tests / 63 assertions / 0 failures |
| `situation-totality-property` alone | never completed | 59.9 s |
| `bin/test seon.schema.datahike-test` | — | 6 tests / 16 assertions / 0 failures |
| `bin/test seon.cluster.agent-test` | already green (19.8 s for the property) | green |

Five consecutive runs of `bin/test seon.cluster.work-test
seon.cluster.agent-test` — 26 tests / 162 assertions / **0 failures, 0 errors**
in every run, with both properties completing every time:

| Run | `situation-totality-property` | `n-agent-parallel-turns-property` |
|---|---|---|
| 1 | 59.2 s | 28.0 s |
| 2 | 61.9 s | 27.3 s |
| 3 | 57.7 s | 26.5 s |
| 4 | 61.7 s | 25.7 s |
| 5 | 56.9 s | 26.1 s |

Runs 1-3 overlapped the old-code bare suite that was wedging on the same
property on the same machine, so these numbers are under adversarial load,
not on an idle box.

### One hypothesis worth refuting explicitly

It is natural to read "300 s of silent file opens under a generative property"
as *per generative sample* — schema resources reloading once per trial,
plausibly connected to the load-time `schema.edn/load!` sentinels catalogued in
[load-time-schema-sentinels-bypass-basis-acquisition](../../../seon/issues/load-time-schema-sentinels-bypass-basis-acquisition.md).
That reading is wrong on both counts and would send a fix lane to the wrong
owner.

- It is **per attribute**, not per sample. `encode-entity`'s `reduce-kv`
  (`datahike.clj:421-425`) called `edn-encoded-attr?` on every key, and the
  three-attribute measurement is exactly three times the one-attribute
  measurement (43.4 ms vs 14.3 ms). A per-sample reload would be flat in
  attribute count.
- It is **not load-time registration**. Nothing is being required or
  registered in the hot loop; `resource-population` is a pure function being
  called from a resolution fallback at `schema.clj:590-599`. The load-time
  sentinel issue is real and separate, and neither causes nor is caused by
  this.

The two findings share an ancestor — declaration authority that is not a value
in hand — which is the audit's Defect I and the reason the seon.env PRD exists.
They do not share an owner or a fix.

## The class regression

`test/seon/schema/datahike-test.clj` —
`encode-transaction-resolves-the-declaration-population-once`. It counts calls
to `schema/registered-schemas` through `with-redefs` and asserts **exactly
one** for a six-attribute transaction, one for a nested entity, and one for a
two-entity `{:tx-data …}` transaction.

It asserts the WANTED behavior — one resolution per transaction — rather than
pinning the deleted per-attribute path, and it fails on the old code by
construction: `encode-entity`'s `reduce-kv` at `datahike.clj:421-425` called
`edn-encoded-attr?` once per attribute, so a six-attribute entity produced six
resolutions. The 43.4 ms ≈ 3 × 14.3 ms measurement above is the same fact
measured instead of counted.

## What is still open (filed, not fixed here)

The **read** side is the same class and is untouched by this change. `seon.db`
calls `schema.datahike/edn-encoded-attr?` per attribute while decoding pulls,
queries, entities, and datoms — `src/seon/db.clj:351, 415, 524, 528, 785` —
so an unsupplied population makes one pull cost 14 ms per attribute. Measured
after this fix: one two-attribute pull = **28.7 ms**, one `q` with no decode =
0.087 ms, one transaction = 15.4 ms (one resolution, as designed).

That fix means threading a projection through five recursive walkers in
`src/seon/db.clj`, a 1,000+ line file with heavy concurrent ownership, and
`seon.db`'s named readers are already inside the seon.env Phase 3 scope. It is
filed as
[db-read-decoding-resolves-declarations-per-attribute](../../../seon/issues/db-read-decoding-resolves-declarations-per-attribute.md)
rather than done here, per cut-first/seam-fix-second and the instruction to
keep this diff surgical.

Second open item, smaller: `situation-totality-property` still takes ~60 s for
200 trials, and essentially all of it is the residual read-side cost above
plus 15 ms per transaction. It is well inside the 300 s liveness backstop and
no longer a gate blocker, but it is a velocity tax that the Phase 1
environment work should erase.

## Ugly output encountered

1. **The liveness diagnostic is excellent and should be the model.** It names
   the last progress event, the isolated root, the git SHA, the selection, and
   writes a virtual-thread-aware JSON dump alongside a readable platform-thread
   supplement. It is the reason this took hours rather than days. No change
   wanted — recorded as calibration.
2. **`bin/test` deletes the evidence for successful runs.** The dumps that
   would have settled this on 2026-08-06 were in `run.8KPLyX`, removed with
   its root. The two that survived did so only because those runs failed for
   other reasons. A liveness dump is expensive evidence of a real defect and
   should outlive its operator root.
3. **The changed-test selector's report is one enormous unreadable line** and,
   for a `src/seon/schema/` file, selects effectively the whole suite in two
   boundaries that then time out. Both facts are already filed
   ([changed-test-report-is-one-enormous-line](../../../seon/issues/changed-test-report-is-one-enormous-line.md));
   this run reproduced them. The line was ~40 KB of clj-kondo warnings with
   the pass/fail summary buried inside it.
4. **A test name that is wrong in the ledger costs a lane its first hours.**
   Not output, but the same class of defect: the hang was recorded by test
   name rather than by the dump's `last-progress` line, and the dump was
   deleted. Recording the diagnostic path next to the claim would have made
   this self-correcting.
