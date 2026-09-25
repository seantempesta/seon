# Lane fix-maintenance-store-refcount (2026-09-25)

What existed: `seon.cluster/acquire-root-store!` / `release-root-store!` own the
process-root store's reference count in `seon.operator.runtime/root-store-holder`.
`seon.maintenance/collect!` (scheduled as `root/maintenance/compact`) bypassed it.
Principal approach: route the one caller through the owner's acquire/release; delete the bypass.

## Broken before (lock audit F3, verified by reading)
- `acquire-operation-store!` (maintenance.clj:521) read the holder map outside the owner's
  monitor, then either used the held store WITHOUT a hold, or called `store/open-store!`
  directly when no cluster held it, releasing with `store/release-store!`.
- Race 1: a cluster's `release-root-store!` could close the store (holders 1 -> 0) while
  collection still ran on it.
- Race 2: while maintenance held its private open, a cluster boot's `acquire-root-store!`
  found no entry and `open-store!` refused `::held-by-this-process` (store.clj:449), so boot failed.
  Detection already exists at the flock; nothing new locks.

## Change
- `with-root-store!` replaces `valid-store?` + `acquire-operation-store!`: one hold through
  `seon.cluster/acquire-root-store!`, one `release-root-store!` in `finally`. Resolved with
  `requiring-resolve` because `seon.cluster` requires `seon.maintenance` via `seon.schedule`
  (same pattern as turn.clj:1069, source.clj:789). Unused `seon.cluster.store` and
  `seon.operator.runtime` requires removed. The unused `supplied` parameter disappears.
- Cost: two map swaps under the owner's existing monitor; no new lock, cache or table.
- Net source lines: +22 / -25 (net -3). Test: +20 (one regression).

## Evidence (live default JVM, scratch namespace, since removed)
Same body as `with-root-store!` against `/Users/sean/src/seon/data/store`:
`{:before 1, :inside 2, :same-handle? true, :after 1, :lock-valid? true, :ms 0.50}`.
The contract `[:=> [:cat :string [:fn clojure.core/ifn?]] :any]` compiles (Malli `m/schema`);
`:any` output because the function returns whatever `f` returns.

## Not verified (LOAD RULE: no test requests from lanes)
Root runs:
- `bin/test-check default --test seon.maintenance-test/maintenance-holds-the-cluster-store-through-its-owner-count`
- `bin/test-check default --ns seon.maintenance-test`
- `bin/test-check default --ns seon.maintenance-schema-test`
The namespace was not reloaded into default (publication paused); root adopts at checkpoint.
The regression needs a running cluster holding the operator root's store
(`store/declared-operator-root`); it fails loudly (`pos-int? before`) otherwise.

## Out of scope
Lock audit row 7 (merge the two holder tables, replace both monitors) remains with its
owner, `src/seon/cluster.clj` / `resources/seon/operator/runtime.clj`.
