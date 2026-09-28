---
type: resume note
lane: health-recording-opus
date: 2026-09-28
status: paused for root's batched restart; nothing committed
---

# Resume: health-recording-opus

Proofs and numbers: `tmp/health_recording/proof.clj` (the hook refuses a hyphenated namespace path, hence the
underscore directory). Bench harness: `tmp/health_recording/bench.clj`.

## Uncommitted, in my files (working tree)

- `src/seon/profile.clj`: histogram cells (40 LongAdder buckets; calls = their sum), entry counter (in flight),
  throws by class, measured mark, keyed roster `[sym digest]` replacing the weak `live` set, `cells-of`,
  reader-neutral `timed`/`exited` (two clock reads, one `*world*` read, self time via `callee-frame`, world counter
  `[calls nanos self calls-stamped self-stamped]`), public `counts`/`histogram-calls`/`quantile-position`/`bucket-at`,
  `observation` (+ p50-us, p99-us, in-flight, throws-by-class), `transaction-observation`, `begin`/`explain` on the
  entry counter. No seon.db dependency (so seon.db can require it).
- `src/seon/profile/measurement.clj` (new): `significant-tx`, `measure`, window/advance!, `measurement-id`.
- `resources/seon/schemas/seon.profile.edn`: new cell keys, window/measurement values, `transaction-observation`,
  `world-counter`, named warnings `:seon.profile/over-bound-warning` (p99 >= 31) and
  `:seon.profile/frequent-throws-warning`.
- `resources/seon/schemas/seon.profile.measurement.edn` (new): the stored measurement entity (noHistory buckets).
- `resources/seon/schemas/seon.db.transaction.edn`: `/ms /calls /hot /repeated`, unindexed (measured reason in the
  file). NOT my file by the ledger: the placement rule (`seon.schema.edn` validate-resource-placement!) requires
  `:seon.db.transaction/*` keys here; tell root.

Lint `--cache false` on both namespaces: 0. Contract compile (seon.contracts-compile-test/check) on both: 0.

## Next steps, in order

1. Convert the callers of the old cell shape in the same commit: `test/seon/test_support.clj:587`
   (`:seon.profile/call-counter` -> `(:seon.profile/calls (profile/observation cell))`), and
   `test/seon/profile_test.clj` `reads-rank...` (hand-increments `call-counter`: use `profile/bucket` +
   histogram). Add tests: histogram/in-flight/throws-by-class; self time into the world counter;
   `transaction-observation` delta; `seon.profile.measurement` baseline/noise/regression/new-digest (use a
   fresh-store fixture with the measurement attribute; `significant-tx` on a store without it answers []).
2. Patches (tmp/health-recording/patches/<owner>/):
   - root: `src/seon/db.clj` stamp-transaction — require `[seon.profile :as profile]`; add
     `:seon.db.transaction/ms :calls :hot :repeated` to its lazily-installed `attributes` vector; merge
     `(profile/transaction-observation world)` into `stamp`. Plus the seon.db.transaction.edn hunk.
   - one-path-impl-2: `src/seon/turn.clj` settle-batch! and `src/seon/turn/batch.clj` settle — one line each:
     `(into (measurement/significant-tx head (executed keys of the entries)))`, computed once outside the retry;
     `src/seon/batch.clj` freeze-step folds slot 2 (self) with `seon.profile/new-adder` instead of its private
     2-slot adder; the fold binds `:seon.batch/stamped-ns (AtomicLong. (System/nanoTime))` in the world; declare it
     in `seon.batch.edn` :world.
   - astra fixer: `src/seon/test.clj` member result carries run-ms; `src/seon/test/runner.clj` complete-members
     writes `:seon.test.member/run-ms` (+ declare in `seon.test.member.edn`) and adds
     `significant-tx` over the members' executed keys (needs health-attribution's test-member world sink).
3. Commit profile/measurement/schemas/tests with `git commit --only`; re-prove on a branch of the live store
   after root's restart (register the final schemas on a new branch; rerun the proof forms against the real
   `seon.profile` once HEAD is loaded — the live wrappers then use the new bracket).
4. Landing note `docs/research/agent-platform/landing/health-recording-2026-09-28.md`; tasks: schema registration
   costs ~300 ms per key of whole-program `seon.schema/contract-references` (3 keys 951 ms, 13 keys 3.9 s);
   ThreadLocalMap growth from `.set prior` leaving nil entries per cell on each thread (bracket variance);
   provenance stamp attributes absent on default's store (JVM predates 089ac703a); AVET index on stamp attributes.

Branch `:health-recording-tx` is disposable (retire it on resume).
