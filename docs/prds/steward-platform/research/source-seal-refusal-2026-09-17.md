---
type: research
status: active
created: 2026-09-17
tags: [publication, write-admission, schema, seon.cluster.source, class/p1]
---

# The source seal refusal: a required cardinality-many key cannot be satisfied

Lane note. Subject: `seon.cluster.source-test` RED at HEAD `ca8fd63b9`
(1 failure + 9 errors reported by two independent agents), every error
`the source seal transaction was refused`
(`:seon.cluster.source/source-seal-refused`) at
`src/seon/cluster/source.clj:626`, with the writer logging only
`:datahike/write-rejected {:kind :transaction/validation-rejected}`.

## Reproduction

`bin/test-fast --paths src/seon/cluster/source.clj -- seon.cluster.source-test`
— one unmodified overlay path, so the runner printed
`snapshot differences from HEAD ca8fd63b9…` with an EMPTY body: the run is
exact HEAD. Slot wait 1035 s, `PHASE test-slot elapsed-seconds=1233`.

## The refusal does travel; the reporter drops it

`seon.cluster.source/require-committed!` (`src/seon/cluster/source.clj:87`)
already carries the transaction result under
`:seon.source/transaction-result`, and `seon.db/transact-call`
(`src/seon/db.clj:3195`) returns `:datahike/validation-refusal` verbatim.
The writer's `:datahike/write-rejected` line is only the bounded log face
(`reference-code/datahike/src/datahike/writer.cljc:157`). What hides the
evidence is `clojure.test`'s uncaught-exception report, which prints the
message and not the `ex-data`. A probe on the SAME packaged projection the
gate arms (`seon.test.arm/packaged-test-projection`,
`src/seon/test/arm.clj:35`) printed it in full:

```
:seon.error/kind        :seon.db/invalid-write
:seon.db/attribute      :seon.activation/config-defaults
:seon.error/diagnostic-cause    :malli.core/missing-key
:seon.error/diagnostic-member   :seon.activation/config-defaults
:seon.error/diagnostic-expected [:set :qualified-keyword]
:seon.error/diagnostic-offending :seon.error/unknown
:seon.db/path           [18 :seon.activation/config-defaults]
:seon.db/entity         #:seon.activation{:source-digest "aaaa…aaaa"}
message: "seon.db/transact! refused transaction data at
          [18 :seon.activation/config-defaults]: expected the required key
          :seon.activation/config-defaults with a set, got a map missing
          :seon.activation/config-defaults."
```

## Root cause

`:seon.activation/closure` (`resources/seon/schemas/seon.activation.edn`)
is a `{:seon.db/attributes true}` STORED-entity schema that declared six
cardinality-many attributes as REQUIRED keys.

The final-report validator landed in `35c5d2fa8` ("Validate every write
grammar against the final transaction entities", the seam described in
`docs/seon/issues/archive/a-partial-upsert-of-an-existing-entity-is-validated-against-its-complete-required-keys.md`).
It rebuilds each affected entity from the RESULTING datoms —
`seon.db/write-entity-value` (`src/seon/db.clj:2912`) folds
`d/datoms … :eavt entity-id` — and validates that row.

A cardinality-many attribute with no members emits NO datoms: `#{}` in
transaction data adds nothing. So an honest closure with no config dials,
no executable symbols and no lookup refs produces an entity that CANNOT
carry those keys, and a required key there is unsatisfiable by
construction. This is the ruled case in AGENTS.md §3 (2026-09-16 §1f G4):
"never encode an event in a collection's cardinality", and the
submission-time check that previously accepted the supplied `#{}` was
exactly the pre-read the authority re-decides.

The refusal was invisible before `35c5d2fa8` because only the SUBMITTED
map was validated, where `#{}` is still a key.

## The fix

`resources/seon/schemas/seon.activation.edn`: the six member collections of
`:seon.activation/closure` are now `{:optional true}`; the identity
`:seon.activation/source-digest` stays required. No validator exemption and
no weakened attribute shape — each collection still validates as
`[:set …]` / `[:vector …]` when present.

Non-emptiness is not lost. It is decided at the authority that can still
see the difference: `seon.cluster.source/activation-seal-tx`
(`src/seon/cluster/source.clj:268`) refuses `::activation-empty` from the
SUPPLIED closure value, before it becomes datoms.

## Measured after the fix

Same probe, same packaged projection:

```
=== COMMITTED ===
#:seon.source{:branch :current-src, :built? true, :digest "aaaa…aaaa",
              :commit-id #uuid "6aab44bd-c6a8-5bf3-acc9-8c734782d44c"}
=== STORED CLOSURE ===
{:db/id 17, :seon.source/digest "aaaa…aaaa",
 :seon.source/built-at #inst "2026-09-17T01:39:09.017-00:00",
 :seon.source/activation-closure
 {:db/id 18,
  :seon.activation/source-digest "aaaa…aaaa",
  :seon.activation/schema-keys [:seon.source/digest],
  :seon.activation/required-attributes [:seon.source/digest]}}
```

The four empty member collections are absent from the stored entity, which
is the measurement that makes the required-key declaration impossible.

Regression:
`seon.cluster.source-test/an-activation-closure-with-empty-member-collections-seals`
asserts the seal commits AND that each empty collection stores no datom.

## The class, derived

The same unsatisfiable declaration exists in eight further stored-entity
schemas. Derived by resolving every attribute form under
`resources/seon/schemas/` to its `[:set …]` / `[:vector …]` head and
listing the non-optional entries of every `{:seon.db/attributes true}` map
(25 keys, of which the 6 fixed here):

| schema | required cardinality-many keys |
|---|---|
| `:seon.activation/closure` | 6 — FIXED here |
| `:seon.cluster/cluster` | `instructions`, `toolkit` |
| `:seon.fn.arity/row` | `arguments` |
| `:seon.maintenance.result/cluster-cleanup-component` | `remaining`, `removed` |
| `:seon.maintenance.result/collect-component` | `collect-branches` |
| `:seon.maintenance.result/process-census-component` | 6 |
| `:seon.maintenance.result/process-census-process` | `advertisements` |
| `:seon.maintenance.result/reap-component` | 4 |
| `:seon.test/adoption` | `adoption-identities`, `adoption-inputs` |

A reap that removed nothing, a census with no unresponsive process, an
arity with no arguments: each is an honest empty that the medium cannot
distinguish from absence, so each will be refused the first time it
occurs. Filed as
[`a-stored-entity-schema-requires-a-cardinality-many-key-that-empty-cannot-satisfy`](../../../seon/issues/a-stored-entity-schema-requires-a-cardinality-many-key-that-empty-cannot-satisfy.md).
Out of this lane's scope: `seon.fn.edn` and `seon.test.edn` are held by the
codex integrator, and the maintenance/cluster rows have their own gates.

## Two further defects in the same namespace, NOT fixed here

1. **The final-report validator wedges a first-party publication.**
   `incremental-first-party-publication-retains-complete-scalar-rows` made
   no reporter progress for 300 s and the suite watchdog exited 124. The
   dump names the WRITER thread inside
   `seon.db/write-report-error` (`src/seon/db.clj:3032`) under
   `datahike.db.transaction/validate-report` (`transaction.cljc:1212`).
   `write-report-error` walks `(distinct (map :e (concat attempted
   (:tx-data report))))` and pulls + Malli-validates EVERY affected
   entity; a complete publication carries ~39 458 entities
   (measured in the sibling issue), on the serial writer loop, with no
   declared bound. A hang is a worse defect than a failure
   (AGENTS.md §2.3). `src/seon/db.clj` is held by the codex integrator, so
   this lane wrote the evidence and stopped. It is slow rather than
   deadlocked: the verification run completed the same test in 196 s under a
   26-second slot wait, so whether it kills the suite depends on machine load.
   Filed as
   [`final-report-validation-runs-unbounded-on-the-writer-thread`](../../../seon/issues/final-report-validation-runs-unbounded-on-the-writer-thread.md).

2. **A stale strictness expectation.**
   `source_test.clj:334` expects `:seon.db/invalid-write` for the sparse
   row `{:seon.fn/sym "seon.id/id" :seon.fn/doc "incomplete"}` against the
   canonical fixture and now gets `nil` — the partial upsert is admitted.
   That is the behaviour `35c5d2fa8` intended for an entity that already
   exists, but the test asserts the old shape and nothing here proves the
   entity existed. Filed as
   [`a-sparse-program-upsert-is-now-admitted-where-the-test-expects-a-refusal`](../../../seon/issues/a-sparse-program-upsert-is-now-admitted-where-the-test-expects-a-refusal.md);
   deciding it needs the write-admission owner, not this lane.

## Verification boundary

`bin/test-fast --paths resources/seon/schemas/seon.activation.edn
test/seon/cluster/source_test.clj -- seon.cluster.source-test` on HEAD
`ad6fe0fdd` (snapshot difference body: those two files and nothing else),
contracts armed `mode= :panic registered= 1102 instrumented= 1102`:

```
Ran 17 tests containing 146 assertions.
1 failures, 0 errors.
```

All nine seal errors are gone and the new regression passed. The one
remaining failure is defect 2 above, `source_test.clj:334`, which predates
this change and is filed.

This is an ITERATION result, not the isolated gate's proof: `bin/test-fast`
shares the worker's contract arming but not its per-worker isolation,
retained run root, platform tier or recorded result facts. No `bin/test`
gate and no `--platform` tier were run by this lane, and no live-cluster
proof was taken: `default` was never stopped, reset, or evaluated against.
