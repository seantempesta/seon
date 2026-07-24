---
type: issue
status: resolved
severity: blocker
tags: [issue, schema, runtime, database]
---

# Fresh boot prevalidation misclassifies core contracts

## Problem

A fresh pod boot validates the compiled core program before the writer asserts
it. That validation passed synthetic `[identity form]` pairs through
`seon.runtime.admission/committed-projection`, whose missing-provenance rule
correctly classifies such rows as agent-authored. The strict contract walker
then rejects documented core opaque-boundary schemas such as `:seon.db/db`.

This is a caller mismatch: desired core program data is not a committed-row
acquisition result and has no asserting transaction yet.

## Evidence

- `src/seon/client.cljs` builds the desired boot program before the
  `ensure-database` request.
- `seon.schema/projection-from-rows` deliberately defaults two-field rows to
  agent-authored.
- Fresh-cluster pod boot failed on the legal `:seon.db/db` `:any` store-id
  element before readiness.
- The writer's actual initialization transaction is stamped with root/boot
  provenance.

## Acceptance

- Desired core program prevalidation uses the core projection compiler without
  fabricating asserting-transaction provenance.
- Missing provenance at the committed-row acquisition boundary remains
  fail-closed as agent-authored.
- A real fresh writer initialization projects asserted core rows as
  core-admitted.
- A fresh isolated cluster reaches pod readiness.

## Current state

The classification fixes and focused regressions landed in `adc25b852`,
`b6ecb55df`, and `ad8eeb582`. Fresh boot now passes both former schema
failures, then stops earlier in database opening because the complete compiled
program exceeds the protocol's hard 4 MiB initialization frame. That distinct
blocker is recorded in
[[unbounded-runtime-acquisitions-exceed-frame]]. This issue remains open until
the fresh readiness gate can run through that owner.

## Resolution — 2026-07-23

Resolved by `adc25b852`, `b6ecb55df`, and `ad8eeb582`, with the formerly
blocked fresh-readiness acceptance completed after paged initialization and
acquisition in `25edc8cff` / `a55419c02`. The desired core population is
compiled with explicit boot provenance at `src/seon/client.cljs:1624-1644`;
the committed-row boundary remains separately fail-closed at
`src/seon/runtime/admission.cljs:209-228`. The recurring identity regression
at `test/seon/client_initialization_test.cljs:224-260` proves the committed
projection equals and reuses the prevalidated core projection.
