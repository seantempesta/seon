---
type: issue
status: resolved
severity: friction
tags: [issue, database, schema]
---

# Bound canonical schema reference resolution

## Problem

The writer's candidate-only schema lookup followed stored Malli references
until the database corpus ended. The protocol frame bounded each transaction,
but many accepted transactions could accumulate an arbitrarily long chain, so
one later attribute use could force unbounded sequential queries and EDN
parses.

## Evidence

`seon.db.writer/canonical-schema-forms` already retained an invocation-local
set that prevented repeated work and made cycles finite, but it imposed no
limit on newly discovered references. A chain persisted across ordinary schema
rows therefore made lookup work proportional to the retained corpus rather
than the one write request.

## Owner

`seon.db.writer/canonical-schema-forms` is the one per-database resolver used
by atomic schema augmentation.

## Acceptance

- One transaction may resolve no more than the documented maximum number of
  unique referenced schema forms beyond its direct candidates.
- Exceeding the limit returns an ordinary `:user-input` failure and advances
  no database coordinate.
- Cycles query and parse each canonical form at most once.

## Resolution

Commit `91a252db` limits one derivation to 64 unique referenced forms while
leaving direct candidate count under the existing request-frame bound. The
long-chain regression returns a protocol failure without a transaction, and a
two-form cycle parses each form exactly once. The writer, receipt, and
generated-ID gate passes 30 tests and 305 assertions.
