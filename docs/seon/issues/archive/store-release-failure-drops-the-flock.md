---
type: issue
status: resolved
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

## Related shape 2026-07-27: half-released instance becomes unaddressable

`seon.cluster/stop!` releases database resources first (by design — the
REPL survives to diagnose), but a throwing `d/release` or
`release-root-store!` skips the remaining teardown while the `finally`
still drops the registry entry (the `marker` matches). The prepl socket
and advertisement then leak with no instance value registered to stop
them — same failure class as this issue's flock drop: a failed release
must leave the resource ADDRESSABLE, never advertised as gone.
Corroborated independently by the Gemini hook review
(tmp/reviews/20260727T164926.037Z.md, finding 2). Fix belongs to the
same stop-semantics pass as the acceptance rows above.

The still-current addressability defect was separated during second-pass
triage into [[../cluster-stop-release-failure-becomes-unaddressable]] so this
resolved store-fence issue does not conceal a second root cause.

## Closed 2026-07-27

Resolved by `5c95e259c`: `src/seon/cluster/store.clj:317-334` calls
`d/release` before `release-flock!`, so a thrown Datahike release leaves the
flock valid and retryable. `test/seon/cluster/store_test.clj:259-278` injects
the failure, proves a second open is refused, then proves a later successful
release permits reopen. The distinct cluster-stop addressability seam remains
open in [[../cluster-stop-release-failure-becomes-unaddressable]].
