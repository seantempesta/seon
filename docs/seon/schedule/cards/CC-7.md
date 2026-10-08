---
title: "Card CC-7: Head writes ready together share one pair of barriers"
type: reference
status: active
created: 2026-10-09
---

# CC-7 Head writes ready together share one pair of barriers

Step 3 ([README](README-commit-cost.md)). Lane: Opus in the konserve fork. Split into fifteen-minute stages along the
proof below; stop and report if a stage needs a Datahike change.

## What exists
Datahike's writer already batches queued transactions of ONE connection into one commit (astra §3.6: 32 items, 2
commits). Distinct branches are distinct connections: each commit's head write pays its own two barriers, measured 8
branches 30.4 ms p50 and 32 branches 156 ms against one connection's 22.4 / 24.5 ms. CC-6 counts 2 barriers per head
write.

## Change
In `konserve.impl.defaults/update-blob`'s `:barriered` path, a store-scoped group: a barriered write that arrives
while no barrier is in flight leads a group at once (no idle wait for one writer); writes arriving while a barrier is
in flight join the next group. A group runs every member's `-sync-data`, one `-sync-store`, every member's move, one
`-sync-store`, then completes each member's callback. Bounds are store config dials validated at open
(`:group-max-writes`, `:group-max-bytes`); a full group closes and the next opens. A member's failure fails that
member; a barrier failure fails every member of its group and none reports success. The group state lives in the
store's existing lifecycle (closed and awaited on release), not a global.

## Functions
- CHANGE `konserve.impl.defaults/update-blob` (`:barriered`), the filestore store lifecycle; config validation for
  the two dials.

## Proof
CC-6's test tightened: two concurrent head writes give 2 `-sync-store` calls; a late arrival lands in the next group;
release awaits the in-flight group. Every CC-6 failure row still delivers no false success. CC-8 proves it through
Datahike.

## Cost
For N head writes ready together: 2 barriers per group instead of 2N, ~8·(1−1/N) ms saved per commit (8 branches:
expected near the one-connection 22 ms instead of 30 ms; 32 branches: near 25 ms instead of 156 ms). One writer:
unchanged (no added wait).

## Durability
Each callback still follows the barrier after its own move, so the head and everything it names are durable before
the commit is acknowledged; nothing acknowledges early.

## Commit
Fork commit; root batches the gitlink bump and adds the two dials to `seon.cluster.store/datahike-configuration`
with their defaults in `config/default.edn` in the same landing.

## Dependencies
CC-6. Gitlink bump: yes.
