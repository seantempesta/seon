---
type: issue
status: resolved
severity: friction
tags: [issue, database, cljs]
---

# Core program test expected an already-converged schema delta

## Problem

`core-program-tx-idempotent-across-boots` still expected a fresh diagnostic
connection's first program delta to contain every registered schema after
`open-agent-conn!` was strengthened to install those canonical program-schema
facts before returning. The production absence was convergence signal; the
stale assertion treated it as missing data.

## Evidence

The isolated selector failed with a frozen desired population of 1,725 schema
rows and zero schema rows in the first `core-program-tx` delta. Source inspection
confirmed that `open-agent-conn!` transacts `index-schemas` before returning.
After changing the assertion to require a nonempty desired population and an
empty schema delta, the exact bootstrap selector and four callable-contract
selectors pass: five tests, 32 assertions, zero failures.

## Owner

`src/seon/client.cljs` owns diagnostic connection convergence.
`test/seon/index_core_test.cljs` owns the obsolete expectation.

## Acceptance

- The test proves the desired schema snapshot is nonempty.
- The first program delta contains no schema rows because the diagnostic
  connection is already converged.
- Transacting the remaining function delta makes the next complete program
  delta empty.
- The isolated bootstrap selector passes.

## Resolution

Resolved in `6c597259`. The isolated bootstrap selector plus the four focused
callable-contract/index selectors pass with 32 assertions and no failures.
