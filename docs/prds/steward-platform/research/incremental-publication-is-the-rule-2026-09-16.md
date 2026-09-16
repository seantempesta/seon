---
type: research
status: active
tags: [publication, performance, schema, config, class/p1]
---

# Incremental publication is the rule

Assignment: R1/R2/R3/R4/R7 and §3 of
[the inventory](recompute-from-scratch-inventory-2026-09-16.md), read end to end,
with AGENTS.md §§0–3 and §§5–7. This note records work in progress, not a
completed proof.

## Inherited evidence

The inventory measured 113 hook cycles, 40 complete decisions out of 47,
46 cycles of at least 30 seconds totaling 6,087 seconds, p90 167 seconds,
and two cycles at the operator's 180-second bound.

The live MCP JVM probe answered at basis 536871865: 339 program file rows
and 182 entries in the declaration stamp. `bin/seon status` reported
default alive, PID 41413. No restart or refork was requested or performed.

Hook log before this change: publication
`404cd646-8093-4134-b145-69fc5ddd3dac`, 2026-09-16T22:11:28.007577Z,
reported complete publication with schema-resource and missing-desired-artifact
among its reasons. Concurrent edits were included in that batch; it is not
an isolated timing of this lane's change.

## Dependency ledger and ownership

- `seon.cluster/incremental-source-refresh!` compares repository-relative
  digests and the cached manifest's relative roots. The relocation repair is
  already present; this lane preserves it.
- `seon.fn/plan-file-change` owns Clojure artifacts. A non-program input has
  no artifact and must not enter that planner.
- `seon.cluster/populate-source!` already owns schema accretion, program
  indexing, and shipped initialization rows in dependency order.
  `declaration-changes` decides additive installation versus incompatibility.
- `seon.cluster.source/upsert!` forks the expected published commit and uses
  Datahike's expected-head guard to publish its scratch branch. The change
  reuses that boundary for non-Clojure population work.
- `reference-code/datahike/src/datahike/db/transaction.cljc` implements
  `:db.fn/call` with the writer's current database. Configuration's existing
  reconcile writer must retain this authority when its pure derivations are
  memoized.
- `seon.issue/index!` owns the note delta. Both complete and incremental
  publication currently invoke it; the Markdown hook itself does not enqueue
  ordinary issue notes. This must remain observable after digest separation.
- `seon.schema.edn/declaration-stamp` names the observable for packaged
  resources; `resource-population` currently reparses them on every call.

## R2 implementation and verification

Non-Clojure inputs are classified before artifact planning: schema resources,
the shipped config document, and inputs with no program facts. Schema/config
changes reuse population on the incremental scratch branch; they do not
require Clojure reanalysis. The fixture regression exercises `refresh-source!`
with actual filesystem edits and checks the decision and published facts.

Verification is incomplete. `bin/test-fast seon.incremental-publication-test`
acquired a shared slot and armed 1,098 contracts at 22:15:23.980801Z. It
entered the regression at 22:15:24.118918Z. A thread observation of its JVM,
PID 80020, showed the main thread still in the initial complete fixture
publication: `seon.fn/index-tempids` → `compile-index-transaction` →
`index!` → `populate-source!` → `full-source-refresh!`.

During review, the draft's population callback was corrected to pass
`:seon.source/previous-database`; without that argument `seon.fn/index!`
refuses a populated scratch branch. The test had loaded the earlier draft.
That superseded invocation was terminated through its own launcher, PID
78612, which reaped its JVM and released its slot. It produced no test
verdict, and the corrected draft has not been rerun. No canonical `bin/test`
gate was launched. Syntax/schema admission and `git diff --check` passed;
these are not behavioral proof.

Fresh live R4 baseline, before any memoization here: 2,785 forms; three
`packaged-forms` calls took 31.170208, 30.386667, and 29.599625 ms. Their
declaration stamps took 1.554750, 1.860000, and 1.589500 ms.

## Shared-tree boundary

Protected runner, program, test-schema, and rendering files have concurrent
edits. None are owned or edited by this lane. Shared hook batches can combine
these edits with this lane's publication; such batches cannot establish an
isolated before/after duration.

## Stop boundary — incomplete work

Additional concurrent edits appeared after this lane began:

- `src/seon/config.clj`: `compile-manifest` was split into
  `compile-settings` plus explicit cluster identity, and `effective` changed.
  `resources/seon/schemas/seon.config.edn` changes with it.
- `src/seon/schema/edn.clj`: `config-dial-form?` changed, with accompanying
  changes in `test/seon/schema/edn_test.clj`.

These files are protected by current uncommitted ownership. The R2 draft's
config route still calls `transact-initialization!`, an upsert-only helper.
It does **not** yet meet exact config reconciliation: removed initialization
attributes/rows would survive. The required owner is the desired-population
and reconciliation portion of `seon.config/apply-compiled!`; its extraction
must preserve the published branch's existing initialization semantics rather
than introducing an arbitrary cluster config row. That file is currently
occupied. R7 also needs its compilation and reconciliation functions; R4
needs `resource-population`/`packaged-forms` in the occupied schema owner.

Per the shared-file stop rule, implementation stops here. R2 is an unverified,
incomplete draft, not a landed fix. R3, R4, and R7 are not implemented by this
lane. No issue was closed. The requested isolated hook before/after table is
not available: the observed batches still hit exit 124 and contain foreign
edits, and the new implementation has not been proven adopted. No timing
improvement is claimed. Default was never restarted or reforked.
