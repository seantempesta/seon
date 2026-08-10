---
type: issue
status: open
severity: friction
tags: [issue, testing, concurrency]
---

# Make the concurrency receipt diagnostic select only failed receipts

## Problem

The long concurrency gate labels successfully settled receipts as failures.
Its diagnostic query returns rows whose error is empty and whose error-kind and
interruption attributes are both absent.

## Evidence

At clean commit `48eb25ab7`, all six scenarios failed the assertion at
`concurrency_independence_test.clj:270`. Returned rows have `""` error and
`:seon.concurrency-independence/absent` for both optional failure fields. The
handoff already described this as “one receipt diagnostic false-fails,” but no
issue note under `docs/seon/issues/` owned it. Evidence:
`tmp/full-gate-2026-08-10b.log:1959-1963` and the same shape at each later
scenario.

## Owner

Suspected owner: `seon.concurrency-independence-test/receipt-rows` and its
failure-selection query; production receipt settlement is not implicated by
these rows.

## Acceptance

- A successfully settled receipt cannot satisfy the failure diagnostic.
- Each genuine error, refusal, or interruption does satisfy it.
- The long gate keeps the exact receipt-identity census and reports zero false
  failures across all six scenarios.
