# astra-cold-projection — rejected cache experiment

The bridge folds a compiled Malli schema to a storage summary. Three boolean
predicates cache by projection; the EDN-codec predicate folds on every call.
The proposed central content cache is slower, so no production change is landed.

## Measured evidence (2026-09-28)

`probe.clj` evaluates local function values, with complete contracts and profile
cells, against one held live database projection. It defines/reloads/replaces no
production Var, starts no test and writes no database transaction. Baseline and
candidate alternate seven times after warm-up; every result is identical.
The candidate is exactly `patches/rejected-content-cache.patch`.

| Same 1,198 installed attributes, four predicates each | Baseline median ms | Candidate median ms | Baseline calls | Candidate calls |
|---|---:|---:|---:|---:|
| New projection-local holder each iteration | 8.306416 | 12.150417 | 13,178 | 38,336 |
| Reused projection-local holder | 4.033583 | 10.425625 | 9,584 | 38,336 |

Raw values and per-function counts: `probe.edn`. MCP result: `result/ee33623c5d4ea`,
362 ms for the whole probe. Counts are the evaluation's call sink, not global
profile deltas. The candidate adds `storage-content`, `cache/lookup`, `cache?`,
`claim`, `join` and `kind-counters` on each predicate call. This dominates the
small storage folds. The experiment fails the owner's faster/simpler bar.
The 512 + 4*printed-content-size byte charge was an estimate, not calibrated
retained heap; no memory saving is claimed.

A separate live probe, `result/e6e32b763a57f`, took 2 ms:

```clojure
(let [db (seon.db/db (seon.cluster.boot/connection "default"))
      p (seon.db/carried-projection db)
      result (seon.db/with db {:tx-data []})
      next-p (when-not (:seon.error/at result)
               (seon.db/carried-projection result))]
  {:same-projection? (identical? p next-p)
   :same-holder? (identical? (:seon.schema.projection/compiled p)
                             (:seon.schema.projection/compiled next-p))
   :same-compiled-attribute?
   (identical? (malli.registry/schema (:seon.schema.projection/registry p) :seon.agent/id)
               (malli.registry/schema (:seon.schema.projection/registry next-p) :seon.agent/id))})
;; => all three true, no error
```

This refutes "each transaction makes a fresh projection". It does not prove
whether declaration-changing merge transactions replace a projection or what
fraction of merge cost their misses explain. The uncached EDN fold exists;
that alone does not prove central caching is profitable.

## Authority and implementation boundaries

Archive base: `14e4a6157ed3bda2a41646956a8cee42f4b5137c`.
Malli gitlink: `43c6dca8d2d8df3162b13725939954807be4a4ed`.
`malli.core/-cached` (core.cljc:353) retains a value on a compiled schema;
`walk` (:2657) walks its children; named reference traversal is :2013–2020.
`seon.schema/projection-registry` (schema.clj:590) retains unaffected roots;
`with-compiled-cache` (:379) creates the projection-local holder.
`seon.cache/lookup` (cache.clj:160) is the bounded content cache owner.
The rejected key resolves references with registry-scoped cycle detection,
preserves the native ref boundary, and never retains a compiled registry in
the central cache. Key construction is proportional to the traversed schema;
subsequent reads use Malli's held key. The shared result is evictable.

Simpler alternative considered: Malli `-cached` around the storage fold itself.
Not landed: it would retain Seon-derived storage summaries outside the central
byte budget. Removing the fold or bypassing armed contracts changes guarantees.
No db.clj edit or patch was needed; it remains astra-db-overhead's boundary.

## Verification and limits

- Final `git archive HEAD` base above, with owned diff only: clj-kondo
  `--lint src script test --cache false`: 0 errors, 1,598 existing warnings,
  no new findings, 7,204 ms. Rejected prototype initially introduced two
  shadowing warnings; both were fixed before measurement.
- Contracts compile checker read the archive source against packaged forms:
  candidate `[]` (157 ms), restored production `[]` (154 ms,
  `result/e8188852f75ca`). No candidate namespace was loaded.
- No tests, test requests, restarts, branch adoption, merge, worktree or
  production namespace reload were performed by this lane.
- prepare-merge! before: owner supplied 2.6–3.1 seconds / 384–473k armed calls.
  After: NOT MEASURED. prepare-merge! calls candidate-gate!, which runs tests;
  the explicit no-tests rule prevents that measurement here. No merge-speedup
  or installed-fix claim is made.
- Default was already faulted. A concurrent root restart made an early MCP
  probe return "not ready"; the local paired probe completed on one held
  projection. Another restart cleared the prototype cache before cleanup
  (kind absent, eviction count 0). No live resources remain owned by this lane.
- Two later diagnostic calls failed: an accidentally unbalanced cleanup form
  returned "The run was evaluated but not recorded"; a subsequent compile
  found contracts-compile-test not loaded after the restart. Explicit require
  followed by cleanup/check succeeded. Neither was counted as a pass.

Root's relevant existing regression requests, if pursuing another approach:
`seon.schema.datahike-test/compiled-storage-navigation-reuses-retained-roots`,
`seon.schema.datahike-test/canonical-population-native-parity`,
`seon.schema.datahike-test/edn-backed-attributes-have-one-canonical-datahike-round-trip`,
`seon.schema.vector-storage-test/vector-attributes-are-declared-by-shape`,
`seon.schema.vector-storage-test/peer-vectors-keep-ref-semantics`, and
`seon.schema-test/retained-generation-recompiles-dependent-roots`.
New coverage would need equal schemas in independent registries sharing one
cache entry, and an unchanged alias form with a changed target missing it.

To reproduce the rejected prototype, apply its patch to the base's datahike.clj
in a disposable file named `tmp/astra-cold-projection/rejected-datahike.clj`;
`probe.clj` reads that file into local function values. Never load it over the
production namespace. The generated archive and candidate file are disposable;
the patch, probe and raw evidence are the durable record.
