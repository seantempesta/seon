---
type: issue
status: open
severity: cleanup
tags: [issue, database, testing]
---

# Select duplicate-identity refusal evidence deterministically

## Problem

Reconciliation correctly refuses duplicate desired identities, but chooses the
reported identity by hash-map iteration. The diagnostic therefore changes with
map representation instead of desired-row order.

This finding is **in flight (schema-edn-consolidation lane)**.

## Evidence

- `src/seon/reconcile.cljc:83-90` selects the first duplicate from
  `(frequencies identities)`.
- A load-only probe over duplicated `:k0` through `:k11` produced a frequency
  key order beginning `(:k8 :k11 :k5 ...)` and reported `:k8`, although `:k0`
  was first in the desired rows.
- `test/seon/reconcile_test.clj` asserts refusal but does not force a hash-map-
  sized duplicate population or stable offense identity.

## Owner

The pure desired-row validation in `seon.reconcile`.

## Acceptance

Duplicate detection preserves the caller's deterministic row order and names
the same offending identity across map thresholds and JVM runs. A regression
uses more than eight distinct duplicated identities.
