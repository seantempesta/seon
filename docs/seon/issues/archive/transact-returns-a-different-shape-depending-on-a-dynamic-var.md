---
type: issue
status: resolved
severity: blocker
tags: [issue, database, runtime, agent, sci]
---

# `seon.db/transact!` returns a different shape depending on a dynamic var

## Problem

`transact-call` chose its return shape from the `db/*conn*` thread binding.
An unbound system call returned Datahike's native transaction report, while
the same call below an agent evaluation returned a Seon projection without
`:db-before` or `:db-after`. A function could therefore work at boot and read
`nil` when an agent called it.

The latent consumer in the fault committer also treated presence of
`:db-after` as the success test while this split contract existed.

## Resolution

Resolved in the commit that archives this issue. `seon.db/transact!` now
returns Datahike's native transaction report on every successful call,
independent of `db/*conn*`. The bespoke `agent-transaction-report` projection
and its configured transaction-datom limit were deleted. The fault committer
recognizes success only when the returned `:db-after` is a database value, so
an arbitrary refusal map cannot be counted as a commit.

The obsolete
`agent-transactions-return-one-bounded-useful-report` test was deleted because
it pinned the removed ambient projection. The surviving boundedness contract
is admission of native transaction reports: database values project to their
reference identities and the admitted artifact remains inline.

## Evidence

- `seon.db-transact-shape-test/transact-return-shape-is-independent-of-dynamic-custody`
  exercises the same contracted function system-side and through a real SCI
  agent evaluation. Both return the native keys `:db-before`, `:db-after`,
  `:tx-data`, `:tempids`, and `:tx-meta` with database values on both sides.
- Focused database gate: 24 tests, 218 assertions, zero failures and errors.
- Focused flow gate: 17 tests, 172 assertions, zero failures and errors.
- The required changed gate's platform tier passed. Its bulk tier awaits the
  separately owned `seon.error-class-schema-test` invalid-schema repair.
- The write-seam diff in `src/seon/db.clj` does not touch read/decode
  functions.

## Model-authoring impact

The transaction return-shape blocker is removed. Model-authored functions may
commit and consume the resulting database value under the same contract as
system-side functions.

## Overnight fixture follow-up — 2026-08-13

The recurring proof went red after the cluster context's replacement reference
became the authoritative environment carrier. Its fixture added a plain
environment beside that state, so `env/of` correctly retained the state's
construction-time value and call preparation had no live connection to
supply. Program-fact and plan probes showed the function's connection slot was
still derived exactly. The fixture now carries a complete environment state,
matching the production ownership boundary; the native transaction-report
contract is unchanged.
