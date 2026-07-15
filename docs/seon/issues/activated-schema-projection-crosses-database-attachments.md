---
type: issue
status: open
severity: blocker
tags: [issue, agent, database, schema]
---

# Scope the activated schema projection to one database attachment

## Problem

The process-global activated Malli program projection and wrappers survive
release of their owning Datahike attachment. Cold replay activates attachment
B's projection before publication reads the old projection, so reconciliation
sees B→B and cannot remove wrappers owned only by A. Stop also releases the
connection without first closing admission or emptying the active projection.

## Evidence

The original ordering inference was false. A fresh isolated selector also
failed in `tmp/test-cljs-20260715-012630-98568.log`: turn two recorded the same
`:malli.core/invalid-schema` fault and omitted the `(tmv 2)` eval row. That
fixture persisted `my.*` function contracts but filtered out every canonical
`:seon.schema` declaration, so its database was independently incomplete.
Keeping the complete schema declaration population makes the isolated test
pass and separates that fixture defect from the real attachment transition.
`tmp/test-cljs-20260715-014326-55059.log` retains the corrected proof: admission
detach/publication, the original current-namespace case, the contaminant-first
A→B case, and a fresh database opened after detach ran as 14 tests with 77
assertions and zero failures or errors. The client lifecycle integration gate
`tmp/test-cljs-20260715-014121-48771.log` additionally proves detach ordering
with runtime lifecycle and instrumentation coverage (36 tests, 312 assertions,
zero failures or errors).

The actual lifecycle loss is source-visible: `start-runtime!` begins
publication, `replay-program-graph!` directly activates B, and only then
`publish-committed!` reads `schema/current-projection`. `stop-runtime!` releases
the Datahike connection while the admission generation, projection, and
wrappers remain live. Exact maintained Datahike source in
`reference-code/datahike/src/datahike/connections.cljc` and
`connector.cljc` confirms `release` drains and invalidates the connection; it
does not own Seon's process-local Malli state.

## Dependency ledger

- Datahike fork `6f90b339768b1a02066dce3b6fcc93a200758fcc`:
  `connections.cljc`, `connector.cljc`, and `writing.cljc` define reference
  release, connection invalidation, and the release-before-delete boundary.
- Malli `0.20.0` at `4c054bd7d042e70d60b83b9f07fb765bc103037f`:
  `reference-code/malli/src/malli/instrument.cljs` owns live wrapper surgery;
  Seon's exact-data reconciliation completes its removal state.
- First-party owners: `seon.runtime.admission` publishes verified projections,
  `seon.schema` holds the one collector/projection state, `seon.instrument`
  reconciles wrappers, and `seon.client` orders replay and attachment release.

This contradicts the runtime-reliability roadmap's retained claim that the
process-global projection contamination was fully repaired. It also blocks an
honest proof that non-autonomous runtime stop/start can replace one database
attachment without inheriting another attachment's activated schema state.

## Owner

`seon.runtime.admission` owns projection publication/detachment and retains the
pre-replay generation inside its existing state. `seon.client` must invoke that
one detach operation before releasing the connection. The declaration fixture
must persist dependency-complete canonical facts.

## Acceptance

- Publication captures A before replay activates B, then reconciles A→B rather
  than B→B.
- Detach reconciles active→empty, activates empty only after verification,
  closes admission, and is idempotent. Failure returns data, leaves the old
  projection retryable, and never claims successful detach.
- The client stop boundary completes detach before Datahike release; failed
  detach retains cleanup authority and the connection for retry.
- A focused contaminant-first test activates an incompatible projection A,
  attaches fresh dependency-complete B, and successfully defines and calls a
  function on B without a schema fault.
- The original `current-ns-persists-across-turns` test passes both alone and in
  its complete-suite order, and its two expected eval rows name
  `:probe.tc.move`.
- The complete CLJS gate contains no `:malli.core/invalid-schema` core fault
  attributable to attachment or test-order contamination.
