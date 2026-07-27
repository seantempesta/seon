---
type: issue
status: open
severity: blocker
tags: [issue, runtime, database, concurrency]
---

# Retain the flock when Datahike release fails

## Problem

`seon.cluster.store/release-store!` releases the flock in `finally` even when
Datahike release throws and the connection remains live. That makes the
physical store available to another JVM while the old writer may still own it,
defeating the exact invariant the flock exists to enforce.

## Evidence

- `src/seon/cluster/store.clj:309-315` calls `d/release` and unconditionally
  invokes `release-flock!` from `finally`.
- An injected `d/release` failure propagated as promised, but
  `connection?` still returned true, the old lock was invalid, and a second
  `open-store!` succeeded. Datahike returned the same still-live connection in
  that JVM; a foreign JVM would see the dropped OS fence and can create the
  forbidden second writer.
- `test/seon/cluster/store_test.clj:87-122` proves successful and idempotent
  release only. The cross-process tests never inject a release failure while a
  child attempts to acquire the store.

## Owner

The `seon.cluster.store` release transition and `held-flocks` table. A failed
connection release is unproved shutdown and must fail closed.

## Acceptance

- The flock and its table entry remain held until Datahike release is proved.
- An injected release failure leaves the connection diagnosable and a foreign
  child JVM refused.
- A later successful retry releases the connection first and only then drops
  the flock, after which another process can open the store.
- Release errors remain visible; safety does not depend on treating absence of
  a lock as proof that the writer stopped.
