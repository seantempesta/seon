---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, database, schema]
---

# Scope the activated schema projection to one database attachment

## Problem

An ordered replay-to-auto-refer test failed with
`:malli.core/invalid-schema` for `:seon.db.coordinate/coordinate`, suggesting
that one disposable attachment had contaminated the next process-global Malli
projection.

## Resolution

Admission detach/publication already repairs the real attachment lifecycle:
detach reconciles the active generation to empty before release, and startup
retains the previous generation across replay before publishing the new one.
The remaining ordered-test failure was not projection materialization or a
Malli-default-registry reset. It was an incomplete diagnostic database.

`seon.client/open-agent-conn!` installed Datahike attributes and identity
policies but no canonical `:seon.schema` facts. An eval-batch that accepted a
new function contract then correctly rebuilt the committed program from that
database and published an empty schema generation. Its following database feed
event exposed the absence while validating
`:seon.db.coordinate/coordinate` through
`seon.schema/valid-candidate-value?`.

Commit `56bf7818` makes the diagnostic connection persist `index-schemas` under boot provenance
as part of its fresh bootstrap. The existing replay proof reads the coordinate
schema back before repeating the identity upsert, so it proves both initial
presence and stable schema identity. No fixture snapshots or post-test process
state restoration are involved.

## Evidence

- `tmp/test-cljs-20260715-054017-94550.log` captures the decisive failure:
  candidate and active coordinate definitions are complete through home
  namespace setup, then both are empty at the eval-batch rejection. The stack
  ends at `seon.schema/valid-candidate-value?`.
- `tmp/test-cljs-20260715-054657-19889.log` runs the exact ordered falsifier:
  `real-non-autonomous-replay-continues-without-a-database-write` followed by
  `after-new-agent-ns-via-batch-resolves-db-alias` (two tests, nine assertions,
  zero failures or errors).
- The focused gate took 17 seconds: approximately 10 seconds compile and seven
  seconds Node execution. A later complete-suite checkpoint should retain this
  as the comparison point for the additional diagnostic bootstrap transaction.

## Dependency ledger

- Datahike fork `6f90b339768b1a02066dce3b6fcc93a200758fcc`:
  `reference-code/datahike/src/datahike/connections.cljc` and
  `connector.cljc` define connection release; they do not own Seon's program
  projection.
- Malli `0.20.0` at `4c054bd7d042e70d60b83b9f07fb765bc103037f`:
  `reference-code/malli/src/malli/core.cljc` confirms explicit registry lookup
  in `m/validate` and the observed `:malli.core/invalid-schema` path.
- First-party owners: `seon.client/open-agent-conn!` owns the isolated
  diagnostic bootstrap, `seon.runtime.admission` rebuilds committed program
  generations, and `seon.schema` owns candidate plus active projection state.

## Owner

`seon.client/open-agent-conn!` owns parity between an isolated diagnostic
database and the canonical program facts required by runtime admission.
`seon.runtime.admission` remains the one publication/detach mechanism.

## Acceptance

- A fresh diagnostic database contains the canonical coordinate schema facts.
- Repeating `index-schemas` retains one identity-addressed schema population.
- Accepting a function contract cannot publish an empty program merely because
  the connection came from `open-agent-conn!`.
- The ordered replay-to-auto-refer falsifier passes without fixture cleanup or
  namespace-order dependence.
