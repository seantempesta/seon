---
type: issue
status: open
tags: [database, schema, issue]
severity: reliability
---

# Arbitrary database results collide with the error shape

## Problem

Public database reads that can return arbitrary user data cannot distinguish a
legitimate map carrying `:seon.error/message` and `:seon.error/kind` from an
untagged database error. Message presence is unsafe; even exact validation of a
closed database-error schema can collide with valid query or pulled data.

## Evidence

The wire protocol already has closed success/failure variants under
`:seon.db.protocol/success?`, but the public facade projects query and pull
successes to their bare values. A query can legitimately return
`{:seon.error/message "x" :seon.error/kind :user-input}`. Fixed-shape results,
by contrast, can remain unwrapped when their registered success schema is
provably disjoint from the closed database-error schema.

## Owner

`seon.db` owns the public result boundary. `seon.result` owns the canonical
outer explicit success/failure discriminator used by collision-capable APIs.

## Acceptance

- The database error schema is closed and one schema-derived predicate owns
  fixed-shape error recognition.
- Every public database operation is inventoried as fixed/disjoint or
  arbitrary/collision-capable.
- Collision-capable results use a closed outer explicit discriminator; a
  legitimate error-shaped domain map remains success data.
- Consumers migrate atomically without a second facade or compatibility path.
- Existing historical eval/result EDN remains untouched.
