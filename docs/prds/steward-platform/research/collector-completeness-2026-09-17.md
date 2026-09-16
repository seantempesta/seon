---
type: research
status: complete
created: 2026-09-17
tags: [research, steward, seon.operator, collect, gc-storage, datahike]
---

# Collector completeness is root verification, not a quiet store (S8 items 1 and 4)

2026-09-17, lane `collector-fix`, branch `steward-platform`.

Closes [the collector reports "incomplete" when writers are live, and then
skips root verification](../../../seon/issues/the-collector-reports-incomplete-when-writers-are-live-and-skips-root-verification.md).
Owned by S8 of
[program-facts-are-the-runtime](../plan/program-facts-are-the-runtime-prd-2026-09-17.md),
items 1 and 4 of its change list. The derived cutoff (item 2) and the
schedule trigger (item 3) are NOT in this slice.

## Paths read end to end before designing

- `src/seon/operator.clj:700-1010` — `collect!`, `collect-store!`,
  `dry-run-store!`, `collection-observation`, `collection-evidence`,
  `evidence-reopens?`, `incomplete-collection!`, and the cleanup arm that
  also calls `collect-store!` (`finish-cluster-cleanup!`).
- `src/seon/cluster/registry.clj:350-547` — `referenced-blobs`,
  `branch-heads`, `physical-filestore-inventory`, `dry-run!`, `collect!`.
- `reference-code/datahike/src/datahike/gc.cljc` — all of it:
  `reachable-in-branch`, `gc-storage!` (the safe point, `remove-before` as a
  MARK-side policy, the `:datahike.gc/*` option map at `:152-164`),
  `start-background-gc!`.
- `reference-code/datahike/src/datahike/gc_guard.cljc` — all of it: the
  values-then-pointer invariant, `safe-point`, the reachability gate.
- `reference-code/konserve/src/konserve/gc.cljc` — `sweep!`, and its
  `:konserve.gc/batch-issued` callback at
  `reference-code/konserve/src/konserve/impl/defaults.cljc:704-705`;
  `-multi-delete-blobs` at `reference-code/konserve/src/konserve/filestore.clj:336`.
- `test/seon/operator_test.clj`, `test/seon/cluster/registry_test.clj`,
  `test/seon/blob_publication_test.clj`, `test/seon/maintenance_test.clj`,
  `test/seon/maintenance_schema_test.clj` — every caller of either `collect!`.
- `docs/seon/issues/the-collector-…md`, PRD §4 S8 and §4b, AGENTS.md §0–§5
  and §7, `tmp/orchestrator/wave2/repl-rule.txt`.

## Cause

`collect-store!` decided completeness as

```clojure
(and (zero? verification-swept) (evidence-reopens? operation-store evidence))
```

Two defects in one expression.

1. **The first conjunct is unreachable on a live store.** `remove-before` is
   `(java.util.Date.)` at both passes, so every write that lands during the
   first pass is judged on its own reachability by the second. A fixed point
   exists only on a quiet store. Correctness never came from there: it comes
   from the store's SAFE POINT (`gc_guard.cljc`), which spares exactly what an
   in-flight values-then-pointer sequence wrote.
2. **`and` short-circuits, so the root check never ran** — and the refusal's
   message said root preservation had been checked and failed. A loud refusal
   about the wrong thing, which is the absence-as-health class inverted.

Evidence: the first real collection on `default` swept 23,449 objects
(3,984,146,099 → 237,934,291 bytes; 25,110 → 1,811 objects) with two lanes
publishing throughout, the second pass swept 1,229, and the call threw
`:seon.operator/collection-incomplete`. Roots were verified by hand afterwards
and were intact
(`research/collect-real-default-2026-09-17.edn`; dry run in
`research/collect-dry-run-default-2026-09-17.edn`, mark 4,790 ms).

A third defect, from S8 item 4 of the issue's own grounding: Datahike's
`gc-storage!` IGNORES unknown option keys, so `{:dry-run? true}` — the
unqualified spelling — reached the entry point, was consulted by nobody, and
performed a REAL collection on `default` once.

## What changed

**`src/seon/operator.clj`**

- `evidence-reopens?` became `root-verification`, which answers a VALUE
  (`:seon.operator.collect/roots-verified?`, `:unstored-digests`, plus
  `:unverified-branch` or `:unverified-digest` when one fails) instead of a
  boolean, and is evaluated UNCONDITIONALLY over the roots the store HELD
  before the sweep (`konserve-key-set`, captured before the first pass).
- `complete?` is now exactly `roots-verified?`.
  `:seon.operator.collect/verification-pass-swept` is still measured and still
  reported; it decides nothing, and `collect!`'s docstring says why it is
  non-zero under live writers.
- The refusal names the failing root in its message and in the result;
  a refusal caused by a THROW now says so instead of claiming a root check.
- The dry run also verifies its roots, because it reads every one of them: a
  candidate inventory for a store that cannot answer for its own roots would
  be the same absence-as-health.
- `inventory-facts` projects one registry inventory into the result's keys, so
  the real path and the dry run report the same four numbers.
- `refuse-misspelled-options!` refuses a request key that carries a documented
  key's name in another namespace, by name, before anything is acquired. The
  documented keys are DERIVED from the declared request schema
  (`documented-request-keys` over `seon.schema.edn/packaged-forms`), never
  mirrored; an absent declaration refuses rather than passing everything. Maps
  stay OPEN: an unrelated key (the scheduler merges its own declared
  maintenance values into this request) is ignored, as before.

**`src/seon/cluster/registry.clj`**

- The real collection path is `collect-and-inventory!`: it takes the dry run's
  inventory from the SAME sweep, in `:konserve.gc/batch-issued`, which runs
  after a batch is fixed and before the backing store's first delete of it.
  `:datahike.gc/batch-size` is the whole sweep, for the same reason the dry run
  uses it — the FileStore's multi-delete is a serial per-key loop either way
  (`filestore.clj:336`), so one batch is the same work and is the only way the
  callback sees the COMPLETE candidate set.
- A caller's own `:konserve.gc/batch-issued` is COMPOSED with, never replaced
  (`blob_publication_test` holds the sweep at that barrier).
- A sweep with no candidates issues no batch; that is not an absent inventory,
  it is the directory with nothing condemned, measured after a sweep that
  deleted nothing.
- `physical-filestore-inventory` now REPORTS missing candidate files rather
  than deciding: the dry run refuses on them (its enumeration is the product),
  the real path does not, because konserve's delete is deliberately miss-safe.
- Arities: 1 and 2 still answer the swept count; 3 answers
  `:seon.cluster.registry/inventory` for both paths.

**Schemas** (accretive; open maps, new keys only):
`seon.operator.collect.edn` declares `dry-run?`, `roots-verified?`,
`unverified-branch`, `unverified-digest`, the four inventory numbers,
`unstored-digests`, `projected-duration-ms`, and two error classes
(`unrecognized-option` + `option-key`, `request-schema-absent` +
`request-schema`); `roots-verified?` is required in the result, every other
new key optional. `seon.cluster.registry.edn` declares the inventory keys and
the `:inventory` map.

## Regressions

- `seon.operator-test/a-nonzero-verification-pass-is-reported-and-decides-nothing`
  — replaces `collection-refuses-a-nonzero-verification-pass-with-partial-evidence`,
  which asserted the defect. N>0 second pass with roots reopening is COMPLETE,
  `verification-pass-swept` N, and the four inventory numbers ride the result.
- `…/collection-refuses-and-names-a-root-that-does-not-reopen` — a digest the
  store held and can no longer read refuses, named in the result and in the
  message; and its second arm, a referenced digest the store NEVER held, is
  counted as `unstored-digests` and is not a refusal.
- `…/collection-refuses-an-undocumented-option-key-by-name` — `{:dry-run? true}`
  is refused by name and sweeps nothing (the stand-in counts its calls).
- `…/collection-ignores-an-unrelated-request-key` — maps stay open.
- `seon.cluster.registry-test/retiring-one-cluster-reclaims-only-its-own-tail`
  — the real path's inventory on a real store (candidates, bytes, retained,
  pre-delete file bytes), and the no-candidate sweep still answering one.

## What the fix immediately found

Root verification, running for the first time, refused a freshly forked
cluster naming digest `a301f012…`. It was not a lost blob: referenced digests
are derived from every attribute whose schema RESOLVES to `:seon.blob/digest`,
and `:seon.db/read-result-digest` is a content digest that is never a konserve
key. Of 33 derived digests on that cluster, exactly ONE was a stored blob.

So the criterion is a DIFFERENTIAL, not a shape test: a digest is verified
only when the store HELD it before the sweep (`held-before`, the konserve key
set captured before the first pass). The rest are counted as
`:seon.operator.collect/unstored-digests` — reported, never dropped, never a
refusal. The underlying missing fact is filed as
[blob roots are derived from digest shape](../../../seon/issues/blob-roots-are-derived-from-digest-shape-not-from-a-declared-fact.md).

## Scratch-root proof

`tmp/collector-fix-root`, cluster `lane` (Juniper seeded), published at
`:current-src` commit `6aaae79f-7b32-5085-8063-29a12e259577`. Every call
through `seon.operator/collect!` in that cluster's own JVM.

1. **Dry run** — `complete? true`, `roots-verified? true`,
   `retained-files 664`, `candidate-files 101`, `candidate-bytes 15,541,200`,
   `mark-duration-ms 529`, `unstored-digests 32`, `swept-objects 0`,
   objects 765 before and after.
2. **Real collection** — `complete? true`, `roots-verified? true`,
   `swept-objects 101`, `reclaimed-bytes 15,541,200`, objects 765 → 664,
   bytes 119,403,812 → 103,862,612, and the SAME four inventory numbers
   (`retained 664`, `candidates 101`, `candidate-bytes 15,541,200`,
   `mark 401 ms`). Reclaimed bytes equal the candidate bytes the inventory
   named before the delete — the denominator and the result agree.
3. **`{:dry-run? true}`** — refused:
   `:seon.operator.collect/unrecognized-option`, `option-key :dry-run?`,
   message `Collection option :dry-run? is not a request key. Did you mean
   :seon.operator.collect/dry-run??`; konserve keys 765 before AND after, so
   it swept nothing. This is the call that performed a real collection on
   `default`.
4. **A root the store held and lost** — `seon.operator/root-verification`
   called directly with the store, real evidence naming two digests, and a
   `held-before` containing only the one that was subsequently removed
   through konserve: `roots-verified? false`,
   `unverified-digest 28e1129f…`, `unstored-digests 1`. With `held-before`
   empty, the same evidence answers `roots-verified? true`,
   `unstored-digests 2`. The end-to-end refusal, with its message, is the
   `collection-refuses-and-names-a-root-that-does-not-reopen` regression.
5. **A non-zero verification pass could NOT be manufactured on this cluster**:
   6,763 concurrent `seon.blob/stage!` writes during a collection still left
   `verification-pass-swept 0`, because this store keeps history and the
   staged blobs stay referenced. The N>0 case is covered deterministically by
   `a-nonzero-verification-pass-is-reported-and-decides-nothing`, and in the
   field by the 1,229 the second pass swept on `default`.

The scratch root was downed and deleted.

## Boundary

- In-process/live proofs on the scratch root `tmp/collector-fix-root`
  (cluster `lane`, Juniper seeded), downed and deleted afterwards. No test JVM
  was launched by this lane; the cold gate is the proof of record.
- Exactly one read-only query against `default` (`jvm` mode, explicit custody)
  for the tests reaching the changed functions.
- The cutoff is UNCHANGED in this slice. The `(java.util.Date.)` sites that
  S8's next slice must derive: `src/seon/operator.clj:892` (dry run),
  `:957` (first pass) and `:974` (verification pass);
  `src/seon/cluster/registry.clj:622` is the 1-arity's beginning-of-time
  default and is not a cutoff decision.
- `seon.maintenance/project-collect-result` still projects the pre-existing
  key list, so `roots-verified?` and the inventory are NOT yet maintenance
  facts. That belongs with S8's trigger slice, which owns the maintenance
  fact family and needs the denominator stored.
